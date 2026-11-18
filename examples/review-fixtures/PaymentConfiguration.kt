package reviewfixture

object PaymentConfiguration {
    fun apiKey(environment: Map<String, String>): String =
        requireNotNull(environment["PAYMENT_API_KEY"]) { "PAYMENT_API_KEY is required" }

    // The callback writes to shared support logs; credentials must stay confidential.
    fun logConfiguration(environment: Map<String, String>, writeSupportLog: (String) -> Unit) {
        val apiKey = apiKey(environment)
        writeSupportLog("Payment credential configured")
    }
}
