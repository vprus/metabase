# Content management review

  - [POST /api/ee/content-management/review/](#post-apieecontent-managementreview)

## `POST /api/ee/content-management/review/`

Create a new `ModerationReview`.

You must be a superuser to do this.

### PARAMS:

*  **`text`** value may be nil, or if non-nil, value must be a string.

*  **`moderated_item_id`** value must be an integer greater than zero.

*  **`moderated_item_type`** value must be one of: `:card`, `:dashboard`, `card`, `dashboard`.

*  **`status`** value must be one of: ``, `verified`.

---

[<< Back to API index](../../api-documentation.md)