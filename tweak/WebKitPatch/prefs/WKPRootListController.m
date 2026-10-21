#import <UIKit/UIKit.h>

// The CI SDK has no Preferences framework, so declare what is needed here. The classes resolve at
// runtime, because the Settings app has Preferences loaded before it opens this bundle.
@interface PSListController : UITableViewController {
    NSArray *_specifiers;
}
- (NSArray *)loadSpecifiersFromPlistName:(NSString *)name target:(id)target;
@end

@interface WKPRootListController : PSListController
@end

@implementation WKPRootListController
- (NSArray *)specifiers {
    if (!_specifiers) {
        _specifiers = [self loadSpecifiersFromPlistName:@"Root" target:self];
    }
    return _specifiers;
}
@end
