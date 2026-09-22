{{- define "payments.labels" -}}
app.kubernetes.io/part-of: payments
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "payments.podSecurity" -}}
runAsNonRoot: true
runAsUser: {{ . }}
runAsGroup: {{ . }}
fsGroup: {{ . }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}
{{- define "payments.containerSecurity" -}}
allowPrivilegeEscalation: false
capabilities:
  drop: [ALL]
{{- end -}}
{{- define "payments.toolResources" -}}
requests: {cpu: 50m, memory: 128Mi}
limits: {cpu: "1", memory: 256Mi}
{{- end -}}
