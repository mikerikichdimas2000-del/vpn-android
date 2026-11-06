package engine

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/netip"
	"strings"
)

// Config повторяет JSON-профиль AmneziaWG из десктопного клиента.
type Config struct {
	PrivateKey string   `json:"privateKey"`
	Addresses  []string `json:"addresses"`
	DNS        []string `json:"dns,omitempty"`
	MTU        int      `json:"mtu,omitempty"`

	Jc   int `json:"jc,omitempty"`
	Jmin int `json:"jmin,omitempty"`
	Jmax int `json:"jmax,omitempty"`
	S1   int `json:"s1,omitempty"`
	S2   int `json:"s2,omitempty"`
	S3   int `json:"s3,omitempty"`
	S4   int `json:"s4,omitempty"`

	H1 string `json:"h1,omitempty"`
	H2 string `json:"h2,omitempty"`
	H3 string `json:"h3,omitempty"`
	H4 string `json:"h4,omitempty"`

	I1 string `json:"i1,omitempty"`
	I2 string `json:"i2,omitempty"`
	I3 string `json:"i3,omitempty"`
	I4 string `json:"i4,omitempty"`
	I5 string `json:"i5,omitempty"`

	HeaderProtectionKey  string `json:"headerProtectionKey,omitempty"`
	ContentPadding       string `json:"contentPadding,omitempty"`
	RekeyAfterTime       string `json:"rekeyAfterTime,omitempty"`
	RekeyTimeout         string `json:"rekeyTimeout,omitempty"`
	RejectAfterTime      string `json:"rejectAfterTime,omitempty"`
	KeepaliveTimeout     string `json:"keepaliveTimeout,omitempty"`
	MaxHandshakeAttempts string `json:"maxHandshakeAttempts,omitempty"`
	RandomTrailers       bool   `json:"randomTrailers,omitempty"`
	DisableCookies       bool   `json:"disableCookies,omitempty"`

	Peer Peer `json:"peer"`
}

// Peer — удалённая сторона туннеля.
type Peer struct {
	PublicKey    string   `json:"publicKey"`
	PresharedKey string   `json:"presharedKey,omitempty"`
	Endpoint     string   `json:"endpoint"`
	AllowedIPs   []string `json:"allowedIps,omitempty"`
	Keepalive    string   `json:"keepalive,omitempty"`
}

// keyToHex переводит base64-ключ WireGuard (32 байта) в hex для UAPI.
func keyToHex(name, b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		// Иногда ключи приходят без паддинга.
		if raw, err = base64.RawStdEncoding.DecodeString(strings.TrimSpace(b64)); err != nil {
			return "", fmt.Errorf("%s: некорректный base64: %w", name, err)
		}
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("%s: ожидались 32 байта, получено %d", name, len(raw))
	}
	return hex.EncodeToString(raw), nil
}

func isZeroKey(b64 string) bool {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return false
	}
	for _, v := range raw {
		if v != 0 {
			return false
		}
	}
	return true
}

// buildUAPI собирает строку конфигурации для device.IpcSet: WireGuard UAPI
// плюс расширения AmneziaWG (Jc/Jmin/Jmax, S1..S4, H1..H4, I1..I5 и тайминги).
func buildUAPI(c *Config) (string, error) {
	if c == nil {
		return "", fmt.Errorf("пустой AWG-профиль")
	}
	var b strings.Builder
	line := func(format string, args ...any) {
		fmt.Fprintf(&b, format+"\n", args...)
	}

	privHex, err := keyToHex("PrivateKey", c.PrivateKey)
	if err != nil {
		return "", err
	}
	line("private_key=%s", privHex)

	if c.Jc > 0 {
		line("jc=%d", c.Jc)
	}
	if c.Jmin > 0 {
		line("jmin=%d", c.Jmin)
	}
	if c.Jmax > 0 {
		line("jmax=%d", c.Jmax)
	}
	for i, v := range []int{c.S1, c.S2, c.S3, c.S4} {
		if v > 0 {
			line("s%d=%d", i+1, v)
		}
	}
	for i, v := range []string{c.H1, c.H2, c.H3, c.H4} {
		if strings.TrimSpace(v) != "" {
			line("h%d=%s", i+1, strings.TrimSpace(v))
		}
	}
	for i, v := range []string{c.I1, c.I2, c.I3, c.I4, c.I5} {
		if strings.TrimSpace(v) != "" {
			line("i%d=%s", i+1, strings.TrimSpace(v))
		}
	}
	if c.HeaderProtectionKey != "" {
		keyHex, err := keyToHex("HeaderProtectionKey", c.HeaderProtectionKey)
		if err != nil {
			return "", err
		}
		line("header_protection_key=%s", keyHex)
	}
	rangeLine := func(key, value string) {
		if v := strings.TrimSpace(value); v != "" {
			line("%s=%s", key, v)
		}
	}
	rangeLine("content_padding_addition", c.ContentPadding)
	rangeLine("rekey_after_time", c.RekeyAfterTime)
	rangeLine("rekey_timeout", c.RekeyTimeout)
	rangeLine("reject_after_time", c.RejectAfterTime)
	rangeLine("keepalive_timeout", c.KeepaliveTimeout)
	rangeLine("max_handshake_attempts", c.MaxHandshakeAttempts)
	if c.RandomTrailers {
		line("random_trailers=true")
	}
	if c.DisableCookies {
		line("disable_cookies=true")
	}

	pubHex, err := keyToHex("Peer.PublicKey", c.Peer.PublicKey)
	if err != nil {
		return "", err
	}
	line("replace_peers=true")
	line("public_key=%s", pubHex)
	if c.Peer.PresharedKey != "" && !isZeroKey(c.Peer.PresharedKey) {
		pskHex, err := keyToHex("Peer.PresharedKey", c.Peer.PresharedKey)
		if err != nil {
			return "", err
		}
		line("preshared_key=%s", pskHex)
	}
	if strings.TrimSpace(c.Peer.Endpoint) == "" {
		return "", fmt.Errorf("не задан Endpoint пира")
	}
	line("endpoint=%s", strings.TrimSpace(c.Peer.Endpoint))

	allowed := c.Peer.AllowedIPs
	if len(allowed) == 0 {
		allowed = []string{"0.0.0.0/0", "::/0"}
	}
	for _, prefix := range allowed {
		p, err := netip.ParsePrefix(strings.TrimSpace(prefix))
		if err != nil {
			return "", fmt.Errorf("AllowedIPs: %q: %w", prefix, err)
		}
		line("allowed_ip=%s", p.Masked().String())
	}
	if ka := strings.TrimSpace(c.Peer.Keepalive); ka != "" {
		line("persistent_keepalive_interval=%s", ka)
	}

	b.WriteString("\n")
	return b.String(), nil
}
