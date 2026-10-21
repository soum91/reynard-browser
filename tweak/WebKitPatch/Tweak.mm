// WebKit Patch: makes the old system WebKit (iOS 16.1.x) behave closer to a current Safari.
//
// This cannot add engine features that are missing from the WebKit binary. What it can do, from
// the UI process of any app that uses WKWebView (Safari, SFSafariViewController, third-party apps):
//   1. inject JavaScript polyfills at document start in every frame,
//   2. optionally advertise a newer Safari version in the user agent,
//   3. optionally switch on WebKit feature flags that this build already contains but ships disabled.
// Everything is read from a plist; defaults are polyfills on, user agent and feature flags off.
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <os/log.h>
#import <substrate.h>
#import "Polyfills.h"

namespace {

static NSArray<NSString *> *prefsPaths() {
    return @[
        @"/var/jb/var/mobile/Library/Preferences/com.soum91.webkitpatch.plist",
        @"/var/mobile/Library/Preferences/com.soum91.webkitpatch.plist",
    ];
}

struct Settings {
    BOOL enabled = YES;
    BOOL polyfills = YES;
    BOOL spoofUserAgent = NO;
    NSString *safariVersion = @"17.6";
    NSArray<NSString *> *enableFeatures = @[];
    NSArray<NSString *> *excludedApps = @[];
    NSArray<NSString *> *excludedHosts = @[];
    BOOL logFeatures = NO;
};

static Settings loadSettings() {
    static Settings cached;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        for (NSString *path in prefsPaths()) {
            NSDictionary *prefs = [NSDictionary dictionaryWithContentsOfFile:path];
            if (![prefs isKindOfClass:[NSDictionary class]]) {
                continue;
            }
            auto flag = [&](NSString *key, BOOL *out) {
                id v = prefs[key];
                if ([v isKindOfClass:[NSNumber class]]) {
                    *out = [v boolValue];
                }
            };
            auto list = [&](NSString *key, NSArray<NSString *> *__strong *out) {
                id v = prefs[key];
                if ([v isKindOfClass:[NSArray class]]) {
                    *out = v;
                }
            };
            flag(@"enabled", &cached.enabled);
            flag(@"polyfills", &cached.polyfills);
            flag(@"spoofUserAgent", &cached.spoofUserAgent);
            flag(@"logFeatures", &cached.logFeatures);
            list(@"enableFeatures", &cached.enableFeatures);
            list(@"excludedApps", &cached.excludedApps);
            list(@"excludedHosts", &cached.excludedHosts);
            id version = prefs[@"safariVersion"];
            if ([version isKindOfClass:[NSString class]] && [version length] > 0) {
                cached.safariVersion = version;
            }
            break;
        }
    });
    return cached;
}

static os_log_t logger() {
    static os_log_t log;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ log = os_log_create("com.soum91.webkitpatch", "tweak"); });
    return log;
}

static BOOL appExcluded(const Settings &s) {
    NSString *bundle = [[NSBundle mainBundle] bundleIdentifier];
    return bundle != nil && [s.excludedApps containsObject:bundle];
}

static const void *kInjectedKey = &kInjectedKey;

static NSString *scriptFor(const Settings &s) {
    NSData *hosts = [NSJSONSerialization dataWithJSONObject:s.excludedHosts options:0 error:nil];
    NSString *hostJSON = hosts ? [[NSString alloc] initWithData:hosts encoding:NSUTF8StringEncoding] : @"[]";
    return [NSString stringWithFormat:
        @"if(!(function(){try{return %@.indexOf(location.hostname)>=0;}catch(e){return false;}})()){\n%@\n}",
        hostJSON, WKPPolyfillSource()];
}

static void injectScript(WKWebViewConfiguration *config, const Settings &s) {
    WKUserContentController *controller = config.userContentController;
    if (controller == nil || objc_getAssociatedObject(controller, kInjectedKey) != nil) {
        return;
    }
    objc_setAssociatedObject(controller, kInjectedKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    WKUserScript *script = [[WKUserScript alloc] initWithSource:scriptFor(s)
                                                  injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                               forMainFrameOnly:NO];
    [controller addUserScript:script];
}

static void applyFeatures(WKWebViewConfiguration *config, const Settings &s) {
    if (s.enableFeatures.count == 0 && !s.logFeatures) {
        return;
    }
    Class prefsClass = [WKPreferences class];
    SEL featuresSel = NSSelectorFromString(@"_features");
    SEL setSel = NSSelectorFromString(@"_setEnabled:forFeature:");
    if (![prefsClass respondsToSelector:featuresSel] || ![config.preferences respondsToSelector:setSel]) {
        return;
    }
    NSArray *features = ((NSArray * (*)(id, SEL)) objc_msgSend)(prefsClass, featuresSel);
    for (id feature in features) {
        NSString *key = [feature valueForKey:@"key"];
        if (s.logFeatures) {
            os_log(logger(), "feature %{public}@ (%{public}@)", key, [feature valueForKey:@"name"]);
        }
        if ([s.enableFeatures containsObject:key]) {
            ((void (*)(id, SEL, BOOL, id)) objc_msgSend)(config.preferences, setSel, YES, feature);
        }
    }
}

typedef id (*InitWithFrameConfigIMP)(id, SEL, CGRect, WKWebViewConfiguration *);
static InitWithFrameConfigIMP originalInit = nullptr;

static id hookedInit(id self, SEL _cmd, CGRect frame, WKWebViewConfiguration *config) {
    const Settings &s = loadSettings();
    if (s.enabled && !appExcluded(s) && config != nil) {
        @try {
            if (s.polyfills) {
                injectScript(config, s);
            }
            if (s.spoofUserAgent && config.applicationNameForUserAgent == nil) {
                config.applicationNameForUserAgent = [NSString stringWithFormat:@"Version/%@ Mobile/15E148 Safari/604.1", s.safariVersion];
            }
            applyFeatures(config, s);
        } @catch (NSException *e) {
            os_log_error(logger(), "patch failed: %{public}@", e);
        }
    }
    return originalInit(self, _cmd, frame, config);
}

} // namespace

__attribute__((constructor)) static void WebKitPatchInit(void) {
    @autoreleasepool {
        Class cls = NSClassFromString(@"WKWebView");
        if (cls == nil) {
            return;
        }
        MSHookMessageEx(cls, @selector(initWithFrame:configuration:), (IMP)hookedInit, (IMP *)&originalInit);
    }
}
