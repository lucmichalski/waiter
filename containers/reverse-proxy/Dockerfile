FROM envoyproxy/envoy-alpine:v1.14.4

COPY envoy-start /opt/waiter/envoy/bin/
COPY envoy-one-port.yaml /etc/envoy/
COPY envoy-two-ports.yaml /etc/envoy/


ENTRYPOINT /opt/waiter/envoy/bin/envoy-start
