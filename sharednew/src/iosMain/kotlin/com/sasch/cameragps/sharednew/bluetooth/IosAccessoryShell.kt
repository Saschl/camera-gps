package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AccessorySetupKit.ASAccessory
import platform.AccessorySetupKit.ASAccessoryEvent
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryAdded
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryChanged
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryRemoved
import platform.AccessorySetupKit.ASAccessoryEventTypeActivated
import platform.AccessorySetupKit.ASAccessoryEventTypeInvalidated
import platform.AccessorySetupKit.ASAccessoryEventTypeMigrationComplete
import platform.AccessorySetupKit.ASAccessoryEventTypePickerDidDismiss
import platform.AccessorySetupKit.ASAccessoryEventTypePickerDidPresent
import platform.AccessorySetupKit.ASAccessoryEventTypePickerSetupFailed
import platform.AccessorySetupKit.ASAccessorySession
import platform.AccessorySetupKit.ASAccessorySupportBluetoothPairingLE
import platform.AccessorySetupKit.ASDiscoveryDescriptor
import platform.AccessorySetupKit.ASErrorCodeActivationFailed
import platform.AccessorySetupKit.ASErrorCodeUserCancelled
import platform.AccessorySetupKit.ASErrorDomain
import platform.AccessorySetupKit.ASMigrationDisplayItem
import platform.AccessorySetupKit.ASPickerDisplayItem
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.UIKit.UIImage
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * AccessorySetupKit mechanics: the [ASAccessorySession], its event stream, the
 * authorized-accessory snapshot and the two pickers. NO policy — what to migrate,
 * when to connect and what the device list shows stay in [IosBluetoothController].
 *
 * Written in Kotlin rather than Swift because Kotlin/Native ships a generated
 * `platform.AccessorySetupKit` binding, so the Swift side can stay the thin shell
 * the project's architecture calls for.
 *
 * Main-thread confined: the session is activated on the main queue, so every
 * callback lands on the same dispatcher the rest of the controller uses.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAccessoryShell(
    private val onAccessoriesChanged: (IosAccessoryShell) -> Unit,
    private val onAccessoryAdded: (identifier: String, displayName: String?) -> Unit,
    private val onAccessoryRemoved: (identifier: String) -> Unit,
    private val onMigrationComplete: () -> Unit,
) {

    /** Outcome of one picker presentation. */
    sealed interface PickerOutcome {
        /** The flow finished; any resulting accessory arrives through the callbacks. */
        data object Completed : PickerOutcome

        /** The person dismissed the picker without choosing anything. */
        data object Cancelled : PickerOutcome

        data class Failed(val message: String, val code: Long) : PickerOutcome
    }

    private val log = logging()

    private val session = ASAccessorySession()

    private val activated = CompletableDeferred<Unit>()
    private var activateRequested = false

    /** Uppercased bluetooth identifier -> accessory, refreshed from the session. */
    private val authorized = mutableMapOf<String, ASAccessory>()

    /**
     * The accessory chosen in the picker. AccessorySetupKit delivers
     * `accessoryAdded` BEFORE `pickerDidDismiss`, and acting on it while the
     * picker is still on screen would run the Sony handshake underneath it.
     */
    private var pendingAccessory: ASAccessory? = null

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Activate the session. Must be called before [showDiscoveryPicker],
     * [showMigrationPicker] or reading [authorizedIdentifiers].
     */
    fun activate() {
        if (activateRequested) return
        activateRequested = true
        log.i { "Activating the AccessorySetupKit session" }
        session.activateWithQueue(dispatch_get_main_queue()) { event -> handleEvent(event) }
    }

    /** Await the `activated` event. Returns false on timeout. */
    suspend fun awaitActivated(timeoutMs: Long = ACTIVATION_TIMEOUT_MS): Boolean {
        activate()
        return withTimeoutOrNull(timeoutMs.milliseconds) { activated.await() } != null
    }

    val isActivated: Boolean get() = activated.isCompleted

    // ---------------------------------------------------------------------------
    // Authorized accessories
    // ---------------------------------------------------------------------------

    fun authorizedIdentifiers(): Set<String> = authorized.keys.toSet()

    fun isAuthorized(identifier: String): Boolean =
        authorized.containsKey(identifier.uppercase())

    fun displayName(identifier: String): String? =
        authorized[identifier.uppercase()]?.displayName

    // ---------------------------------------------------------------------------
    // Pickers
    // ---------------------------------------------------------------------------

    /**
     * Present the system picker so the person can authorize a new camera.
     * Must be driven by an explicit user action.
     */
    suspend fun showDiscoveryPicker(): PickerOutcome {
        if (!awaitActivated()) return PickerOutcome.Failed("AccessorySetupKit did not activate", ASErrorCodeActivationFailed)
        val item = ASPickerDisplayItem(
            name = PICKER_ITEM_NAME,
            productImage = productImage(),
            descriptor = sonyDescriptor(),
        )
        log.i { "Presenting the discovery picker" }
        return presentPicker(listOf(item))
    }

    /**
     * Present the migration flow for cameras that were paired before
     * AccessorySetupKit.
     *
     * The list must contain ONLY migration items. AccessorySetupKit shows an
     * informational page for a migration-only picker and migrates every item;
     * mixing in a regular display item turns it back into a discovery picker and
     * migrates nothing unless a brand-new accessory is set up.
     */
    suspend fun showMigrationPicker(candidates: List<PendingMigration>): PickerOutcome {
        if (candidates.isEmpty()) return PickerOutcome.Completed
        if (!awaitActivated()) return PickerOutcome.Failed("AccessorySetupKit did not activate", ASErrorCodeActivationFailed)

        val image = productImage()
        val items = candidates.map { candidate ->
            ASMigrationDisplayItem(
                name = candidate.displayName,
                productImage = image,
                descriptor = sonyDescriptor(),
            ).apply {
                setPeripheralIdentifier(NSUUID(uUIDString = candidate.identifier))
            }
        }
        log.i { "Presenting the migration picker for ${items.size} camera(s)" }
        return presentPicker(items)
    }

    /**
     * Remove the accessory from the system, which also drops the Bluetooth bond.
     * This is what makes "forget this camera" work without sending people to
     * Settings.
     */
    suspend fun remove(identifier: String): Boolean {
        val accessory = authorized[identifier.uppercase()] ?: run {
            // Nothing to revoke: the camera was never confirmed through the
            // picker, so its system pairing is not ours to remove and the user
            // has to do it in Settings.
            log.w { "Cannot remove $identifier: it is not an authorized accessory" }
            return false
        }
        val error = suspendCancellableCoroutine { continuation ->
            session.removeAccessory(accessory) { error -> continuation.resume(error) }
        }
        if (error != null) log.w { "removeAccessory failed: ${error.localizedDescription}" }
        return error == null
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private suspend fun presentPicker(items: List<Any>): PickerOutcome {
        val error = suspendCancellableCoroutine { continuation ->
            session.showPickerForDisplayItems(items) { error -> continuation.resume(error) }
        }
        return when {
            error == null -> PickerOutcome.Completed
            error.domain == ASErrorDomain && error.code == ASErrorCodeUserCancelled ->
                PickerOutcome.Cancelled

            else -> {
                log.w { "Picker failed: ${error.localizedDescription} (${error.code})" }
                PickerOutcome.Failed(error.localizedDescription, error.code)
            }
        }
    }

    private fun handleEvent(event: ASAccessoryEvent?) {
        val type = event?.eventType ?: return
        when (type) {
            ASAccessoryEventTypeActivated -> {
                refreshAuthorized()
                log.i { "AccessorySetupKit activated with ${authorized.size} accessory(ies)" }
                activated.complete(Unit)
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypeMigrationComplete -> {
                refreshAuthorized()
                log.i { "Migration complete; ${authorized.size} accessory(ies) authorized" }
                onAccessoriesChanged(this)
                onMigrationComplete()
            }

            ASAccessoryEventTypeAccessoryAdded -> {
                // Held until pickerDidDismiss so setup does not run under the picker.
                pendingAccessory = event.accessory
                refreshAuthorized()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypePickerDidDismiss -> {
                val accessory = pendingAccessory
                pendingAccessory = null
                if (accessory != null) {
                    val id = accessory.identifierString()
                    if (id != null) {
                        log.i { "Accessory added: $id (${accessory.displayName})" }
                        onAccessoryAdded(id, accessory.displayName)
                    } else {
                        log.w { "Accessory added without a bluetooth identifier" }
                    }
                }
            }

            ASAccessoryEventTypeAccessoryRemoved -> {
                val id = event.accessory?.identifierString()
                refreshAuthorized()
                onAccessoriesChanged(this)
                if (id != null) {
                    log.i { "Accessory removed: $id" }
                    onAccessoryRemoved(id)
                }
            }

            ASAccessoryEventTypeAccessoryChanged -> {
                refreshAuthorized()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypeInvalidated -> {
                // The session cannot be reused. Nothing here recreates it: that
                // would need a fresh object, and the app has no way to recover
                // the picker mid-flight anyway.
                log.e { "AccessorySetupKit session invalidated" }
                authorized.clear()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypePickerSetupFailed -> log.w { "Accessory setup failed" }

            ASAccessoryEventTypePickerDidPresent -> log.d { "Picker presented" }

            else -> log.d { "Unhandled AccessorySetupKit event $type" }
        }
    }

    private fun refreshAuthorized() {
        authorized.clear()
        session.accessories.filterIsInstance<ASAccessory>().forEach { accessory ->
            accessory.identifierString()?.let { authorized[it] = accessory }
        }
    }

    private fun ASAccessory.identifierString(): String? =
        bluetoothIdentifier?.UUIDString?.uppercase()

    private fun productImage(): UIImage =
        UIImage.systemImageNamed(PRODUCT_IMAGE_SYMBOL) ?: UIImage()

    private fun sonyDescriptor(): ASDiscoveryDescriptor = ASDiscoveryDescriptor().apply {
        // Matches what the Android CompanionDeviceManager filter already does.
        // Sony cameras do not advertise their 128-bit service UUID, so the
        // company identifier is the only usable matcher. It MUST also appear in
        // NSAccessorySetupBluetoothCompanyIdentifiers or the app crashes here.
        setBluetoothCompanyIdentifier(SONY_COMPANY_ID)
        setSupportedOptions(ASAccessorySupportBluetoothPairingLE)
    }

    private companion object {
        const val ACTIVATION_TIMEOUT_MS = 5_000L
        const val SONY_COMPANY_ID: UShort = 0x012Du
        const val PICKER_ITEM_NAME = "Sony camera"
        const val PRODUCT_IMAGE_SYMBOL = "camera.fill"
    }
}
