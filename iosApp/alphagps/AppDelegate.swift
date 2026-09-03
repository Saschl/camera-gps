import UIKit
import sharedKit

/// AppDelegate adapter that ensures the CoreBluetooth central manager is
/// created **before** iOS delivers the `willRestoreState` callback.
///
/// When iOS terminates the app and later relaunches it in the background
/// for a Bluetooth event (e.g. a previously-connected camera comes back
/// in range), it expects the app to recreate the `CBCentralManager` with
/// the same restore identifier within ≈ 10 seconds. By calling
/// `ensureInitialized()` here we guarantee this happens on every launch
/// path – foreground and background alike.
///
/// The launch options are recorded first, into a dependency-free object:
/// on a background launch no UI is ever attached, so this is the only
/// chance to learn *why* the process exists. Without it a failed state
/// restoration and a user force-quit look identical in the logs.

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        IosLaunchContext.shared.record(
            bluetooth: launchOptions?[.bluetoothCentrals] != nil,
            location: launchOptions?[.location] != nil
        )
        IosBluetoothController.shared.ensureInitialized()
        return true
    }
}
