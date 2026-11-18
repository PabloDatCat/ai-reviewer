package reviewfixture

class OrderStatusApi {
    // Public v1 JSON response. Existing clients require the "order_id" field.
    fun response(orderId: String, status: String): Map<String, String> =
        mapOf("orderId" to orderId, "status" to status)
}
