# Manual review fixtures

These isolated Kotlin examples are acceptance inputs, outside the CLI source sets.
They do not call payment services or use real credentials.

Create each PR with **base `test/review-baseline`**, then add `ai-review`.
All five branches descend from the same baseline. Each PR changes one Kotlin file.
Do not merge the intentionally defective branches into the reviewer application.

| Head branch | Scenario | Expected review |
| --- | --- | --- |
| `test/clean-refactoring` | Rename a private shipping predicate and its only caller | No findings |
| `test/business-logic-bug` | Free shipping excludes orders at exactly 5,000 cents | BUSINESS_LOGIC: 499 cents charged instead of zero |
| `test/exposed-secret` | Replace environment configuration with a plaintext dummy API key | Security scope probe; secret detection is outside the current five categories. Ignoring environment configuration can separately be a contract finding. |
| `test/race-condition` | Replace atomic shipment claim with check-then-write | RACE_CONDITION: two workers can both claim the same order |
| `test/breaking-api-change` | Rename the existing JSON field `order_id` to `orderId` | BACKWARD_COMPATIBILITY or CONTRACT_VIOLATION: existing clients lose a required field |

The value on the exposed-secret branch is deliberately fictional and cannot authenticate.
Passing these examples does not replace fork, link navigation, queue or timeout acceptance.
