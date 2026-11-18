# Isolated manual review scenarios

Open each PR against `review-test/baseline`. The five head branches are independent.
Each PR changes one Kotlin fixture and adds harmless formatting/naming noise.
These files are outside the CLI source sets and are never executed by the reviewer.

| Branch | Intended substantive change |
| --- | --- |
| `review-test/clean-refactoring` | Private predicate and its only caller are renamed; behavior stays the same |
| `review-test/business-logic-bug` | Exactly 5,000 cents no longer qualifies for free shipping |
| `review-test/exposed-secret` | A configured API key is written verbatim to shared support logs; environment lookup and validation stay intact |
| `review-test/race-condition` | Two concurrent workers can both claim the same order |
| `review-test/breaking-api-change` | The v1 JSON field `order_id` changes to `orderId` |

For a manual secret-leak demonstration, pass only a fictional value:

```kotlin
PaymentConfiguration.logConfiguration(
    mapOf("PAYMENT_API_KEY" to "EXAMPLE_ONLY_NOT_A_REAL_SECRET"),
    ::println,
)
```

Never pass a real credential. These fixtures do not call any external API.
Secret disclosure remains outside the reviewer's current five review categories;
that PR is a security scope probe, without an unrelated authentication/configuration bug.
Formatting and naming noise should not produce findings in any of the five PRs.
Apply `ai-review` manually when ready. Do not merge the deliberately defective cases.
