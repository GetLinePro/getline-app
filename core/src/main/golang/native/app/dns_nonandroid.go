//go:build !android

package app

// NotifyDnsChanged is Android-only in production. The host stub keeps the
// application-owned config package testable without changing Android behavior.
func NotifyDnsChanged(_ string) {}
