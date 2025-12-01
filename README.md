## Common HTTP status codes:

200 - OK (success)
204 - No Content (success, no body)
400 - Bad Request (client error)
404 - Not Found (resource doesn't exist)
405 - Method Not Allowed (wrong HTTP method)
500 - Internal Server Error (server error)

# The HTTP protocol requires this two-step process:

Headers first (includes status code, content-length, CORS, etc.)
Body second (the actual data/content)
This is why you must call sendResponseHeaders() before writing to the response body, and why the content length parameter must match what you actually write.
