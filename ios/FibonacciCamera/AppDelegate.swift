import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        #if targetEnvironment(simulator)
        Log.info("launch · Simulator build · demo camera feed")
        #else
        Log.info("launch · iPhone build · real camera")
        #endif
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = CameraViewController()
        window?.makeKeyAndVisible()
        return true
    }
}
