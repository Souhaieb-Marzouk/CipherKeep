# CipherKeep zero-knowledge relay

Deploy your own Worker + R2 bucket, then set a random bearer token:

```sh
wrangler r2 bucket create cipherkeep-relay
wrangler secret put TOKEN
wrangler deploy
```

The Worker stores only opaque `application/octet-stream` bodies under
`/v1/<vault-id>/manifest` and `/v1/<vault-id>/blobs/<item-id>-<version>`.
It has no listing route, logs no request bodies or headers, and never sees vault
keys or plaintext. Requests require `Authorization: Bearer <TOKEN>`.
