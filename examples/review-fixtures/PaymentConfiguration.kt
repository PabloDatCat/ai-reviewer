package reviewfixture

object PaymentConfiguration {
    fun apiKey(environment: Map<String, String>): String =
        requireNotNull(environment["PAYMENT_API_KEY"]) { "PAYMENT_API_KEY is required" }
}
