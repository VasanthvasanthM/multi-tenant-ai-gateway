# B11 — Start and stop

The exact commands are in
[`runbooks/starting-everything.md`](../runbooks/starting-everything.md) — 329
lines, kept current, and the thing to copy from. **This file is the why**: the
order, and what breaks when you get it wrong.

---

## The dependency order

Seven steps, and every one has to come before the next for a reason.

```
1. Docker Desktop        nothing else exists without the daemon
2. the cluster           k3d, MetalLB, ingress-nginx
3. Redis and PostgreSQL  the gateway crashes on startup without them
4. the gateway           Flyway migrates on boot
5. observability         Prometheus, Grafana, Loki, Tempo, Alertmanager
6. port-forwards         or none of the UIs are reachable
7. alerting              needs Alertmanager AND the rules
```

### 1 → 2: why the daemon first

k3d runs k3s **inside Docker containers**. No daemon, no nodes. `k3d cluster
start` against a stopped Docker Desktop fails in a way that looks like a cluster
problem.

### 2 → 3: why MetalLB and ingress come with the cluster

```bash
SKIP_RAM_CHECK=1 HTTP_PORT=18080 HTTPS_PORT=18443 ./scripts/cluster-up.sh
```

`cluster-up.sh` installs MetalLB and ingress-nginx as part of bringing the
cluster up, not afterwards. Without them a `Service` of type `LoadBalancer`
stays `Pending` forever — on a laptop there is no cloud provider to hand out an
IP, so MetalLB *is* the load balancer.

The custom ports exist because 8080 is usually taken on this machine.

### 3 → 4: why the databases must be first

The gateway runs **Flyway migrations on boot**. No Postgres, no migration, and
the pod crash-loops. Redis is needed just as early: the API-key lookup fails
closed, so every request would 401 even if the pod started.

> **This is the ordering that matters most**, and the symptom of getting it wrong
> is a crash-loop that looks like a bad image rather than a missing dependency.

### 4 → 5: why observability is last, not first

It is the most expensive part of the stack on a 7.4 GB laptop — Prometheus,
Grafana, Loki, Alloy, Tempo and Alertmanager together outweigh everything they
observe. Starting it before the thing being measured means competing for the
memory that the measurement needs.

### 5 → 6: the port-forwards

```bash
kubectl port-forward -n monitoring svc/kps-grafana 13000:80 &
kubectl port-forward -n monitoring svc/kps-kube-prometheus-stack-alertmanager 19093:9093 &
kubectl port-forward svc/aigw-ai-gateway-gateway 18081:8080 &
```

Nothing inside the cluster is reachable from the host without these. Every URL in
the demo script depends on one.

> **One trap, learned the hard way:** two competing port-forward retry loops once
> fought over port 13000 — 101 restarts — and hammered the API server while doing
> it. One plain forward per port, and no auto-retry wrapper.

---

## When it wedges — which it will

On this host, repeatedly:

```
kubectl hangs with: TLS handshake timeout
docker logs k3d-ai-gateway-server-0: handler timeouts,
  "Network Policy Controller heartbeat missed", or logs that simply stop
```

The runbook's fix:

```bash
k3d cluster stop ai-gateway && sleep 10 && k3d cluster start ai-gateway
```

And when that is not enough — the full recovery, used four times in one session:

```
1. quit Docker Desktop entirely
2. wsl --shutdown                      ← releases the VM's memory to Windows
3. relaunch Docker Desktop, wait for the daemon
4. k3d cluster start ai-gateway
```

### The root cause was never Kubernetes

```
7.4 GB total RAM, and 215 MB free while Docker Desktop runs
```

`PLEG is not healthy` and `TLS handshake timeout` are both **memory starvation
symptoms**. The kubelet cannot complete its pod-lifecycle sweep inside the
deadline because it is not being scheduled. k3s is not broken; it is starved.

Which is why the cheaper fix often works:

```bash
docker restart k3d-ai-gateway-server-0      # ~20 seconds
```

### And one genuinely obscure failure

After a restart, the node IPs swapped and the kubelet's serving certificate no
longer matched:

```
certificate is valid for 172.19.0.4, not 172.19.0.3
```

The fix is to delete the certificate and let the kubelet reissue it:

```bash
# inside the node container
rm /var/lib/rancher/k3s/agent/serving-kubelet.crt \
   /var/lib/rancher/k3s/agent/serving-kubelet.key
docker restart k3d-ai-gateway-server-0
```

**A cert pinned to an IP that Docker reassigned.** Nothing in the message
suggests restarting is the fix.

---

## What to check before trusting anything

```bash
kubectl get nodes                      # Ready?
kubectl get pods -A                    # anything CrashLoopBackOff?
kubectl get svc                        # did the LoadBalancer get an IP?
kubectl logs deploy/aigw-ai-gateway-gateway | head -40
```

That last one is the most informative on this project, because the gateway
announces its own degraded modes at startup:

```
EMBEDDING FALLBACK ACTIVE - no model directory at ...
```

**If the banner is there, Tier 2 will serve nothing**, and any cache measurement
you take is Tier-1 only. Reading the first forty log lines is the difference
between measuring the system and measuring a fallback.

---

## Tear down

```bash
k3d cluster stop ai-gateway     # keep it, free the RAM
k3d cluster delete ai-gateway   # gone, including the data
```

**Stop, not delete, almost always.** Deleting loses the Postgres volume, so the
next start re-runs Flyway on an empty database and every cached answer and usage
row is gone — which quietly invalidates any measurement you were comparing
against.

And one warning from the runbook worth repeating:

> *"VM was restarted with different limits, and the load numbers will not
> reproduce."*

If `.wslconfig` changes between runs, the measurements are not comparable.
**Record the VM shape with the result or the result is not a result.**

---

## The shortest version

```bash
# up
k3d cluster start ai-gateway
until kubectl get nodes >/dev/null 2>&1; do sleep 8; done
kubectl apply -f deploy/k8s/dev-infra.yaml
helm upgrade --install aigw deploy/helm/ai-gateway -f deploy/helm/ai-gateway/values-dev.yaml
kubectl port-forward svc/aigw-ai-gateway-gateway 18081:8080 &

# check
kubectl get pods
kubectl logs deploy/aigw-ai-gateway-gateway | head -40

# down
k3d cluster stop ai-gateway
```

Everything else — observability, alerting, the demo URLs, what is stored in each
table — is in
[`runbooks/starting-everything.md`](../runbooks/starting-everything.md).

---

**Previous:** [B10a — where every number came from](B10a-where-every-number-came-from.md).
**Next:** [B12 — testing with k3d and k6](B12-testing-with-k3d-and-k6.md).
