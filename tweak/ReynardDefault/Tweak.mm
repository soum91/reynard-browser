// Reynard Default Browser: sends web links that other apps hand to Safari to Reynard instead.
//
// Reynard accepts http(s) links directly and also reynard://open?url=<percent-encoded URL>.
// The second form names the target app explicitly, so it works whether or not iOS lists Reynard
// as the default browser. This is a redirect of link handoffs; it does not replace the WebKit
// engine that other apps use inside their own web views.
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <mach-o/dyld.h>
#import <substrate.h>

namespace {

static NSString *const kPrefsPaths[] = {
    @"/var/jb/var/mobile/Library/Preferences/com.soum91.reynarddefault.plist",
    @"/var/mobile/Library/Preferences/com.soum91.reynarddefault.plist",
};
static NSString *const kReynardBundleID = @"com.minh-ton.Reynard";

// Sign-in pages stay in whatever opened them, because their redirects must come back to the app.
static NSArray<NSString *> *defaultExcludedHosts() {
    return @[
        @"accounts.google.com", @"appleid.apple.com", @"login.microsoftonline.com",
        @"login.live.com", @"id.apple.com", @"github.com/login",
        // Native-app links iOS should keep handling itself.
        @"apps.apple.com", @"itunes.apple.com", @"music.apple.com", @"podcasts.apple.com",
        @"maps.apple.com", @"books.apple.com", @"tv.apple.com", @"wallet.apple.com",
        @"icloud.com/photos", @"facetime.apple.com",
    ];
}

struct Settings {
    BOOL enabled = YES;
    BOOL redirectSafariViewController = YES;
    NSString *targetBundleID = nil;
    NSArray<NSString *> *excludedHosts = nil;
    NSArray<NSString *> *excludedApps = nil;
};

static Settings loadSettings() {
    static Settings cached;
    static CFAbsoluteTime loadedAt = 0;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        cached.targetBundleID = kReynardBundleID;
        cached.excludedHosts = defaultExcludedHosts();
        cached.excludedApps = @[];
    });
    CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
    if (loadedAt != 0 && now - loadedAt < 3.0) {
        return cached;
    }
    loadedAt = now;
    for (NSString *path in kPrefsPaths) {
        NSDictionary *prefs = [NSDictionary dictionaryWithContentsOfFile:path];
        if (![prefs isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        id enabled = prefs[@"enabled"];
        if ([enabled isKindOfClass:[NSNumber class]]) {
            cached.enabled = [enabled boolValue];
        }
        id sfsvc = prefs[@"redirectSafariViewController"];
        if ([sfsvc isKindOfClass:[NSNumber class]]) {
            cached.redirectSafariViewController = [sfsvc boolValue];
        }
        id target = prefs[@"targetBundleID"];
        if ([target isKindOfClass:[NSString class]] && [target length] > 0) {
            cached.targetBundleID = target;
        }
        id hosts = prefs[@"excludedHosts"];
        if ([hosts isKindOfClass:[NSArray class]]) {
            cached.excludedHosts = [defaultExcludedHosts() arrayByAddingObjectsFromArray:hosts];
        }
        id apps = prefs[@"excludedApps"];
        if ([apps isKindOfClass:[NSArray class]]) {
            cached.excludedApps = apps;
        }
        break;
    }
    return cached;
}

static BOOL targetInstalled(NSString *bundleID) {
    static CFAbsoluteTime checkedAt = 0;
    static BOOL installed = YES;
    CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
    if (checkedAt != 0 && now - checkedAt < 5.0) {
        return installed;
    }
    checkedAt = now;
    Class workspaceClass = NSClassFromString(@"LSApplicationWorkspace");
    id workspace = [workspaceClass respondsToSelector:@selector(defaultWorkspace)]
        ? [workspaceClass performSelector:@selector(defaultWorkspace)] : nil;
    SEL installedSel = NSSelectorFromString(@"applicationIsInstalled:");
    if (workspace && [workspace respondsToSelector:installedSel]) {
        BOOL (*call)(id, SEL, NSString *) = (BOOL (*)(id, SEL, NSString *))objc_msgSend;
        installed = call(workspace, installedSel, bundleID);
    } else {
        installed = YES; // can't tell, so don't strand links
    }
    return installed;
}

static BOOL hostExcluded(NSURL *url, NSArray<NSString *> *rules) {
    NSString *host = url.host.lowercaseString;
    NSString *hostAndPath = [host stringByAppendingString:url.path ?: @""].lowercaseString;
    for (NSString *rule in rules) {
        NSString *r = rule.lowercaseString;
        if ([r containsString:@"/"]) {
            if ([hostAndPath hasPrefix:r]) return YES;
        } else if ([host isEqualToString:r] || [host hasSuffix:[@"." stringByAppendingString:r]]) {
            return YES;
        }
    }
    return NO;
}

static NSURL *reynardURL(NSURL *webURL) {
    NSMutableCharacterSet *allowed = [[NSCharacterSet alphanumericCharacterSet] mutableCopy];
    [allowed addCharactersInString:@"-._~"];
    NSString *encoded = [webURL.absoluteString stringByAddingPercentEncodingWithAllowedCharacters:allowed];
    return encoded ? [NSURL URLWithString:[@"reynard://open?url=" stringByAppendingString:encoded]] : nil;
}

// Returns the reynard:// URL to open instead, or nil to leave the link alone.
static NSURL *redirected(NSURL *url) {
    if (![url isKindOfClass:[NSURL class]]) return nil;
    NSString *scheme = url.scheme.lowercaseString;
    if (![scheme isEqualToString:@"http"] && ![scheme isEqualToString:@"https"]) return nil;
    if (url.host.length == 0) return nil;
    Settings s = loadSettings();
    if (!s.enabled) return nil;
    if (hostExcluded(url, s.excludedHosts)) return nil;
    if (!targetInstalled(s.targetBundleID)) return nil;
    return reynardURL(url);
}

static BOOL processExcluded() {
    NSString *bundleID = NSBundle.mainBundle.bundleIdentifier ?: @"";
    if ([bundleID isEqualToString:kReynardBundleID] || [bundleID hasPrefix:[kReynardBundleID stringByAppendingString:@"."]]) return YES;
    if ([bundleID isEqualToString:@"com.apple.mobilesafari"]) return YES;
    return [loadSettings().excludedApps containsObject:bundleID];
}

// MARK: - UIApplication

static BOOL (*orig_openURL)(id, SEL, NSURL *);
static BOOL hook_openURL(id self, SEL _cmd, NSURL *url) {
    NSURL *target = redirected(url);
    return orig_openURL(self, _cmd, target ?: url);
}

static void (*orig_openURLOptions)(id, SEL, NSURL *, NSDictionary *, void (^)(BOOL));
static void hook_openURLOptions(id self, SEL _cmd, NSURL *url, NSDictionary *options, void (^completion)(BOOL)) {
    // Apps that ask for universal links only want an app, not a browser.
    BOOL universalOnly = [options[UIApplicationOpenURLOptionUniversalLinksOnly] boolValue];
    NSURL *target = universalOnly ? nil : redirected(url);
    orig_openURLOptions(self, _cmd, target ?: url, target ? @{} : options, completion);
}

static BOOL (*orig_privateOpenURL)(id, SEL, NSURL *);
static BOOL hook_privateOpenURL(id self, SEL _cmd, NSURL *url) {
    NSURL *target = redirected(url);
    return orig_privateOpenURL(self, _cmd, target ?: url);
}

// MARK: - SFSafariViewController

static const void *kSFURLKey = &kSFURLKey;

static id (*orig_sfInitConfig)(id, SEL, NSURL *, id);
static id hook_sfInitConfig(id self, SEL _cmd, NSURL *url, id config) {
    id result = orig_sfInitConfig(self, _cmd, url, config);
    if (result) objc_setAssociatedObject(result, kSFURLKey, url, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return result;
}

static id (*orig_sfInitReader)(id, SEL, NSURL *, BOOL);
static id hook_sfInitReader(id self, SEL _cmd, NSURL *url, BOOL reader) {
    id result = orig_sfInitReader(self, _cmd, url, reader);
    if (result) objc_setAssociatedObject(result, kSFURLKey, url, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return result;
}

static void (*orig_sfDidAppear)(id, SEL, BOOL);
static void hook_sfDidAppear(id self, SEL _cmd, BOOL animated) {
    orig_sfDidAppear(self, _cmd, animated);
    if (!loadSettings().redirectSafariViewController) return;
    NSURL *url = objc_getAssociatedObject(self, kSFURLKey);
    NSURL *target = redirected(url);
    if (!target) return;
    objc_setAssociatedObject(self, kSFURLKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    UIApplication *app = UIApplication.sharedApplication;
    [app openURL:target options:@{} completionHandler:^(BOOL opened) {
        if (opened) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [(UIViewController *)self dismissViewControllerAnimated:NO completion:nil];
            });
        }
    }];
}

// MARK: - Installing hooks

static void hookMethod(Class cls, SEL sel, IMP replacement, IMP *original) {
    if (cls && class_getInstanceMethod(cls, sel)) {
        MSHookMessageEx(cls, sel, replacement, original);
    }
}

static BOOL safariServicesHooked = NO;
static void hookSafariViewController() {
    if (safariServicesHooked) return;
    Class cls = NSClassFromString(@"SFSafariViewController");
    if (!cls) return;
    safariServicesHooked = YES;
    hookMethod(cls, NSSelectorFromString(@"initWithURL:configuration:"), (IMP)hook_sfInitConfig, (IMP *)&orig_sfInitConfig);
    hookMethod(cls, NSSelectorFromString(@"initWithURL:entersReaderIfAvailable:"), (IMP)hook_sfInitReader, (IMP *)&orig_sfInitReader);
    hookMethod(cls, @selector(viewDidAppear:), (IMP)hook_sfDidAppear, (IMP *)&orig_sfDidAppear);
}

static void imageAdded(const struct mach_header *, intptr_t) {
    // SafariServices loads lazily in most apps; hook the view controller once it is there.
    if (!safariServicesHooked && NSClassFromString(@"SFSafariViewController")) {
        dispatch_async(dispatch_get_main_queue(), ^{ hookSafariViewController(); });
    }
}

} // namespace

__attribute__((constructor))
static void ReynardDefaultInit() {
    @autoreleasepool {
        if (processExcluded()) return;
        Class app = UIApplication.class;
        hookMethod(app, @selector(openURL:), (IMP)hook_openURL, (IMP *)&orig_openURL);
        hookMethod(app, @selector(openURL:options:completionHandler:), (IMP)hook_openURLOptions, (IMP *)&orig_openURLOptions);
        hookMethod(app, NSSelectorFromString(@"_openURL:"), (IMP)hook_privateOpenURL, (IMP *)&orig_privateOpenURL);
        hookSafariViewController();
        _dyld_register_func_for_add_image(imageAdded);
    }
}
