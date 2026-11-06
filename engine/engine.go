// Package engine — мобильное ядро AmneziaWG для Android.
//
// Пакет экспортируется в Kotlin через gomobile bind. Туннель создаётся
// поверх файлового дескриптора, полученного от VpnService, поэтому
// приложению не нужен root и отдельный бинарник.
package engine

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/sys/unix"
)

var (
	mu      sync.Mutex
	dev     *device.Device
	tunDev  *fdTun
	upSince time.Time
)

// SocketProtector исключает UDP-сокеты ядра из Android VPN-маршрутизации.
// Его реализация на Kotlin вызывает VpnService.protect().
type SocketProtector interface {
	Protect(fd int) bool
}

type protectedBind struct {
	conn.Bind
	protector SocketProtector
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	peek, ok := b.Bind.(conn.PeekLookAtSocketFd)
	if !ok {
		_ = b.Bind.Close()
		return nil, 0, errors.New("UDP bind не предоставляет файловые дескрипторы для protect")
	}
	protected := 0
	for _, peekFD := range []func() (int, error){peek.PeekLookAtSocketFd4, peek.PeekLookAtSocketFd6} {
		fd, peekErr := peekFD()
		if peekErr != nil || fd < 0 {
			continue
		}
		if !b.protector.Protect(fd) {
			_ = b.Bind.Close()
			return nil, 0, fmt.Errorf("VpnService.protect не принял UDP-сокет %d", fd)
		}
		protected++
	}
	if protected == 0 {
		_ = b.Bind.Close()
		return nil, 0, errors.New("не удалось получить UDP-сокеты для protect")
	}
	return fns, actualPort, nil
}

// Start поднимает туннель поверх Android TUN-дескриптора и заранее защищает
// UDP-сокеты AmneziaWG от повторного попадания в сам туннель.
func Start(fd int, configJSON string, protector SocketProtector) error {
	mu.Lock()
	defer mu.Unlock()

	if protector == nil {
		_ = unix.Close(fd)
		return errors.New("не задан Android socket protector")
	}
	fdOwned := true
	defer func() {
		if fdOwned {
			_ = unix.Close(fd)
		}
	}()
	if dev != nil {
		return errors.New("туннель уже запущен")
	}
	var cfg Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("конфигурация: %w", err)
	}
	uapi, err := buildUAPI(&cfg)
	if err != nil {
		return err
	}
	t, err := newFdTun(fd, cfg.MTU)
	if err != nil {
		return fmt.Errorf("tun: %w", err)
	}
	fdOwned = false // fdTun / device.Close теперь отвечают за дескриптор.

	logger := device.NewLogger(device.LogLevelError, "[awg] ")
	d := device.NewDevice(t, &protectedBind{Bind: conn.NewDefaultBind(), protector: protector}, logger)
	if err := d.IpcSet(uapi); err != nil {
		d.Close()
		return fmt.Errorf("применение конфигурации: %w", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return fmt.Errorf("запуск устройства: %w", err)
	}

	dev = d
	tunDev = t
	upSince = time.Now()
	return nil
}

// Stop останавливает туннель. Повторный вызов безопасен.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	return stopLocked()
}

func stopLocked() error {
	if dev == nil {
		return nil
	}
	dev.Close()
	if tunDev != nil {
		tunDev.Close()
	}
	dev = nil
	tunDev = nil
	upSince = time.Time{}
	return nil
}

// statsJSON — форма ответа Stats для Kotlin.
type statsJSON struct {
	Connected  bool  `json:"connected"`
	RxBytes    int64 `json:"rxBytes"`
	TxBytes    int64 `json:"txBytes"`
	Handshake  int64 `json:"handshakeMs"`
	UptimeMs   int64 `json:"uptimeMs"`
	ListenPort int   `json:"listenPort"`
}

// Stats возвращает состояние туннеля и счётчики трафика в виде JSON.
func Stats() (string, error) {
	mu.Lock()
	defer mu.Unlock()

	out := statsJSON{}
	if dev != nil {
		out.Connected = true
		out.UptimeMs = time.Since(upSince).Milliseconds()
		if raw, err := dev.IpcGet(); err == nil {
			parseStats(raw, &out)
		}
	}
	encoded, err := json.Marshal(out)
	if err != nil {
		return "{}", err
	}
	return string(encoded), nil
}

// parseStats разбирает UAPI-вывод устройства.
func parseStats(raw string, out *statsJSON) {
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch key {
		case "rx_bytes":
			if v, err := strconv.ParseInt(value, 10, 64); err == nil {
				out.RxBytes = v
			}
		case "tx_bytes":
			if v, err := strconv.ParseInt(value, 10, 64); err == nil {
				out.TxBytes = v
			}
		case "last_handshake_time_sec":
			if v, err := strconv.ParseInt(value, 10, 64); err == nil && v > 0 {
				out.Handshake = v * 1000
			}
		case "listen_port":
			if v, err := strconv.Atoi(value); err == nil {
				out.ListenPort = v
			}
		}
	}
}

// Version возвращает версию ядра — удобно показывать в настройках/логах.
func Version() string {
	return "amneziawg-go/v3"
}
