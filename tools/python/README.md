# Python tools

`mock_1c.py` is a dependency-free local mock for the 1C event endpoint.

Run:

```bash
python3 tools/python/mock_1c.py --port 18081 --token dev-1c
```

Point the Go gateway to it:

```bash
ADDR=:8080 \
GATEWAY_TOKEN=dev-mobile \
ONE_C_BASE_URL=http://127.0.0.1:18081 \
ONE_C_TOKEN=dev-1c \
go run ./cmd/gateway
```
