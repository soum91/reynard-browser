#import <Preferences/PSListController.h>
#import <Preferences/PSSpecifier.h>

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
