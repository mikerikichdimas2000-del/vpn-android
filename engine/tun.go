package engine

import (
	"errors"
	"os"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/unix"
)

// errTunClosed возвращается читателю/писателю после остановки туннеля.
var errTunClosed = errors.New("tun: устройство закрыто")

// fdTun — минимальная реализация tun.Device поверх файлового дескриптора,
// который Android выдаёт VpnService. ядро amneziawg-go ожидает один пакет
// за операцию (BatchSize = 1) и полный IP-пакет без дополнительных заголовков.
type fdTun struct {
	file      *os.File
	mtu       int
	events    chan tun.Event
	closeOnce sync.Once
	closeErr  error
}

func newFdTun(fd, mtu int) (*fdTun, error) {
	if fd < 0 {
		return nil, errors.New("некорректный файловый дескриптор туннеля")
	}
	if mtu <= 0 {
		mtu = 1280
	}
	// Неблокирующий режим переводит дескриптор под poller Go: закрытие файла
	// корректно прерывает заблокированные Read/Write при остановке.
	if err := unix.SetNonblock(fd, true); err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), "tun")
	if f == nil {
		return nil, errors.New("не удалось открыть дескриптор туннеля")
	}
	t := &fdTun{file: f, mtu: mtu, events: make(chan tun.Event, 1)}
	t.events <- tun.EventUp
	return t, nil
}

func (t *fdTun) File() *os.File { return t.file }

func (t *fdTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, nil
	}
	n, err := t.file.Read(bufs[0][offset:])
	if err != nil {
		if errors.Is(err, os.ErrClosed) {
			return 0, errTunClosed
		}
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (t *fdTun) Write(bufs [][]byte, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, nil
	}
	if _, err := t.file.Write(bufs[0][offset:]); err != nil {
		if errors.Is(err, os.ErrClosed) {
			return 0, errTunClosed
		}
		return 0, err
	}
	return 1, nil
}

func (t *fdTun) MTU() (int, error) { return t.mtu, nil }

func (t *fdTun) Name() (string, error) { return "tun", nil }

func (t *fdTun) Events() <-chan tun.Event { return t.events }

func (t *fdTun) BatchSize() int { return 1 }

func (t *fdTun) Close() error {
	t.closeOnce.Do(func() {
		t.closeErr = t.file.Close()
		select {
		case t.events <- tun.EventDown:
		default:
		}
		close(t.events)
	})
	return t.closeErr
}
