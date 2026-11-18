package reviewfixture

class ShippingPrice {
    fun feeCents(orderCents: Int): Int {
        require(orderCents >= 0)
        return if (hasFreeShipping(orderCents)) 0 else 499
    }
    // Orders of at least 5,000 cents must ship free.
    private fun hasFreeShipping(orderCents: Int): Boolean = orderCents >= 5_000
}
