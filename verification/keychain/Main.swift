import UIKit
import Security
import DebugKeychainReview

@main
final class KeychainReviewApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        self.window = window
        DispatchQueue.main.async {
            let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: "gycrosskit-review-keychain-20261008", kSecAttrAccount: "fixture"]
            print("Swift SecItemDelete status=\(SecItemDelete(query as CFDictionary))")
            fflush(stdout)
            print(KeychainProbeKt.keychainReplacementCheck())
            exit(0)
        }
        return true
    }
}
