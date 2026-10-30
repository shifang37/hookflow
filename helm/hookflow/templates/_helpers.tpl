{{/*
Common names and labels. The release name is part of every name unless
fullnameOverride is set, so two installs in one namespace do not collide.
*/}}

{{- define "hookflow.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "hookflow.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "hookflow.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "hookflow.labels" -}}
helm.sh/chart: {{ include "hookflow.chart" . }}
{{ include "hookflow.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "hookflow.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hookflow.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "hookflow.image" -}}
{{- printf "%s:%s" .Values.image.repository (.Values.image.tag | toString) -}}
{{- end -}}

{{/*
Secret name: the chart-created one, or the operator-provided existingSecret.
*/}}
{{- define "hookflow.secretName" -}}
{{- if .Values.secrets.create -}}
{{- include "hookflow.fullname" . -}}
{{- else -}}
{{- required "secrets.existingSecret is required when secrets.create=false" .Values.secrets.existingSecret -}}
{{- end -}}
{{- end -}}
