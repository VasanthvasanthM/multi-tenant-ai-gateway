# Alerting setup — Slack

Everything except the webhook URL is already configured in
[`deploy/k8s/observability/kube-prometheus-stack-values.yaml`](../../deploy/k8s/observability/kube-prometheus-stack-values.yaml).
Alertmanager is enabled, both receivers are written, and the alert rules live in
[`deploy/k8s/observability/alerts/`](../../deploy/k8s/observability/alerts/).

**The only manual step is creating the Secret**, because the webhook URL is a
credential: it must not go in git, in `helm get values`, in CI logs, or into a
chat window. Anything pasted into one of those has to be rotated.

---

## Step 1 — Get a Slack webhook URL

1. <https://api.slack.com/apps> → **Create New App** → **From scratch**
2. Name it (e.g. `AI Gateway Alerts`), pick your workspace
3. Left sidebar → **Incoming Webhooks** → toggle **On**
4. **Add New Webhook to Workspace** → choose the channel → **Allow**
5. Copy the URL. It looks like:
   `https://hooks.slack.com/services/T000.../B000.../XXXXXXXX`

The default channel in the config is **`#ai-gateway-alerts`**. Create that
channel first, or change `channel:` in the values file to one you have.

Slack's free tier is fine for this.

## Step 2 — Create the Secret

Run this **yourself**. Do not paste the URL into a chat, a commit, or a file.

```bash
kubectl create secret generic alertmanager-slack -n monitoring \
  --from-literal=webhook-url='https://hooks.slack.com/services/XXX/YYY/ZZZ'
```

The key **must** be `webhook-url` — the config reads it from
`/etc/alertmanager/secrets/alertmanager-slack/webhook-url`.

## Step 3 — Apply the config

```bash
SKIP_RAM_CHECK=1 ./scripts/observability-up.sh --profile metrics
```

> On a small cluster this Helm upgrade has timed out repeatedly and taken the
> API server with it. If it fails, clear the stuck release and retry:
>
> ```bash
> helm list -n monitoring              # look for pending-upgrade
> helm rollback kps -n monitoring
> ```

## Step 4 — Restart Alertmanager so it reads the Secret

Alertmanager does **not** watch the mounted file. After creating **or rotating**
the Secret:

```bash
kubectl delete pod -n monitoring -l app.kubernetes.io/name=alertmanager
```

Skipping this is the usual reason "I created the Secret and nothing arrives".

## Step 5 — Verify it actually notifies

Reaching the UI:

```bash
kubectl port-forward -n monitoring svc/kps-kube-prometheus-stack-alertmanager 9093:9093 &
# http://localhost:9093
```

Confirm the Secret is mounted where the config expects:

```bash
kubectl exec -n monitoring alertmanager-kps-kube-prometheus-stack-alertmanager-0 \
  -c alertmanager -- ls /etc/alertmanager/secrets/alertmanager-slack/
```

Send a real test alert — this is the only check that proves the whole path,
since a correct-looking config that silently goes nowhere is the failure mode
this setup is designed against:

```bash
curl -XPOST http://localhost:9093/api/v2/alerts -H 'Content-Type: application/json' -d '[{
  "labels": {"alertname":"SlackPipeTest","severity":"warning"},
  "annotations": {"summary":"Testing the Slack path end to end"}
}]'
```

A message should appear in the channel within ~30 s (`group_wait`). If nothing
arrives:

```bash
kubectl logs -n monitoring -l app.kubernetes.io/name=alertmanager -c alertmanager --tail=50
```

Look for `notify retry` or `"msg":"Notify for alerts failed"`.

---

## What is already configured

Four rules in `alerts/replica-lag.yaml`, including one that fires when the lag
metric **stops being reported** — a missing series and a healthy replica look
identical otherwise.

Routing decisions, which are the part worth reviewing rather than copying:

| Setting | Value | Why |
|---|---|---|
| `group_by` | `alertname` + `severity` | a cluster-wide problem arrives as ONE message, not one per pod |
| `group_wait` | 30 s | lets a burst coalesce before the first message |
| `repeat_interval` | **4 h** (1 h for critical) | five-minute repeats train people to mute the channel, and a muted channel is worse than no alerting |
| `inhibit_rules` | critical suppresses matching warning | otherwise one incident pages twice and the second message buries the first |

Alert text includes the `runbook_url` annotation when a rule sets one. An alert
that only says "something is wrong" costs a human the same ten minutes every
time it fires.

## Rotating the webhook

```bash
kubectl delete secret alertmanager-slack -n monitoring
kubectl create secret generic alertmanager-slack -n monitoring \
  --from-literal=webhook-url='NEW_URL'
kubectl delete pod -n monitoring -l app.kubernetes.io/name=alertmanager
```

## If you would rather not use Slack

Alertmanager speaks several transports; swap the `receivers` block in the values
file. All of them take their credential the same way — a Secret, referenced by a
`*_file` setting, never a literal.

| Transport | Receiver key | Credential |
|---|---|---|
| Email | `email_configs` | SMTP password via `auth_password_file` |
| Discord | `discord_configs` | webhook via `webhook_url_file` |
| Telegram | `telegram_configs` | bot token via `bot_token_file` |
| PagerDuty | `pagerduty_configs` | integration key via `routing_key_file` |
| Anything else | `webhook_configs` | plain HTTP POST to your own endpoint |
