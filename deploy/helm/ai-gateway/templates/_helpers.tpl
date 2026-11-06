{{/*
Shared template helpers.

Everything here exists so the per-resource templates can `range` over
.Values.services instead of repeating themselves five times. Five copies of a
Deployment drift: a security context gets tightened in one and forgotten in the
others, and nothing fails until an audit finds it.
*/}}

{{- define "ai-gateway.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-gateway.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "ai-gateway.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Labels applied to every object.

app.kubernetes.io/* are the names every Kubernetes tool already understands, so
`kubectl get all -l app.kubernetes.io/instance=<release>` works without anyone
learning this chart's conventions.
*/}}
{{- define "ai-gateway.labels" -}}
helm.sh/chart: {{ include "ai-gateway.chart" . }}
{{ include "ai-gateway.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: ai-gateway
{{- end }}

{{- define "ai-gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Per-service selector labels.

A Deployment's selector is IMMUTABLE after creation. Getting these wrong means
the next `helm upgrade` fails with "field is immutable" and the only fix is
deleting the Deployment - which drops every pod at once. They are defined in
exactly one place for that reason.

Takes a dict of (root, name).
*/}}
{{- define "ai-gateway.componentSelectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-gateway.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .name }}
{{- end }}

{{- define "ai-gateway.componentLabels" -}}
helm.sh/chart: {{ include "ai-gateway.chart" .root }}
{{ include "ai-gateway.componentSelectorLabels" . }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: ai-gateway
{{- end }}

{{- define "ai-gateway.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- include "ai-gateway.fullname" . }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image reference for a service. Takes (root, name).

The tag defaults to the chart's appVersion rather than `latest`. A `latest` tag
makes a rollback impossible - `helm rollback` restores the manifest, and the
manifest still says `latest`, which still resolves to the broken image.
*/}}
{{- define "ai-gateway.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- $tag := .root.Values.global.imageTag | default .root.Chart.AppVersion -}}
{{- printf "%s/ai-gateway-%s:%s" $registry .name $tag -}}
{{- end }}

{{/*
Environment shared by every service: infrastructure addresses and JVM settings.

Credentials come from an existing Secret via secretKeyRef, never from values.
A password in values.yaml is visible in `helm get values`, in CI logs, and in
whatever git repository the values file lives in.
*/}}
{{- define "ai-gateway.commonEnv" -}}
- name: TZ
  value: {{ .Values.global.timezone | quote }}
- name: SPRING_DATA_REDIS_HOST
  value: {{ .Values.infrastructure.redis.host | quote }}
- name: SPRING_DATA_REDIS_PORT
  value: {{ .Values.infrastructure.redis.port | quote }}
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .Values.infrastructure.postgres.existingSecret }}
      key: {{ .Values.infrastructure.postgres.usernameKey }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.infrastructure.postgres.existingSecret }}
      key: {{ .Values.infrastructure.postgres.passwordKey }}
- name: R2DBC_URL
  value: {{ printf "r2dbc:postgresql://%s:%v/%s"
            .Values.infrastructure.postgres.host
            .Values.infrastructure.postgres.port
            .Values.infrastructure.postgres.database | quote }}
- name: JDBC_URL
  value: {{ printf "jdbc:postgresql://%s:%v/%s"
            .Values.infrastructure.postgres.host
            .Values.infrastructure.postgres.port
            .Values.infrastructure.postgres.database | quote }}
{{- if .Values.infrastructure.kafka.enabled }}
- name: KAFKA_ENABLED
  value: "true"
- name: KAFKA_BOOTSTRAP
  value: {{ .Values.infrastructure.kafka.bootstrapServers | quote }}
{{- end }}
{{- end }}
