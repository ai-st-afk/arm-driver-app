# syntax=docker/dockerfile:1

FROM golang:1.23-alpine AS build
WORKDIR /src/backend/gateway
COPY backend/gateway/go.mod ./
RUN go mod download
COPY backend/gateway/ ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/arm-driver-gateway ./cmd/gateway

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=build /out/arm-driver-gateway ./arm-driver-gateway

ENV HTTP_PORT=8080 \
    DATA_DIR=/app/data

EXPOSE 8080
ENTRYPOINT ["./arm-driver-gateway"]
