package reviewfixture

import java.util.concurrent.ConcurrentHashMap

class ShipmentClaims {
    private val claimed = ConcurrentHashMap<String, Boolean>()

    // Concurrent workers start a shipment only after a successful claim.
    // A given order must be shipped at most once.
    fun tryClaim(orderId: String): Boolean {
        if (claimed.containsKey(orderId)) return false
        claimed[orderId] = true
        return true
    }
}
