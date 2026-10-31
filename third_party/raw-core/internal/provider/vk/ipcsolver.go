package vk

import (
	"context"
	"errors"
	"net"

	"github.com/samosvalishe/free-turn-proxy/internal/captchaipc"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"
	manualcaptcha "github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha/manual"
)

// InteractiveHooks applies only to hosts implementing the authenticated IPC.
// Ordinary CLI/manual integrations retain their existing behavior.
type InteractiveHooks struct {
	BrowserSolver ManualSolverFunc
	BeforeFetch   func(context.Context) error
	OnAuthWait    func()
	OnAuthSuccess func()
}

func IPCSolver(broker *captchaipc.Server, manual bool) ManualSolverFunc {
	return func(ctx context.Context, e *captcha.Error, dialer net.Dialer, profile browserprofile.Profile) (string, error) {
		if err := broker.Acquire(ctx); err != nil {
			return "", err
		}
		defer broker.Release()
		solveCtx, cancel := context.WithCancel(ctx)
		defer cancel()
		mode := "browser"
		if manual {
			mode = "manual"
		}
		var id string
		var presentErr error
		token, err := manualcaptcha.SolveViaProxyWithPresenterQuiet(solveCtx, e.RedirectURI, dialer, profile, func(raw string) {
			id, presentErr = broker.Begin(solveCtx, raw, mode)
			if presentErr != nil {
				cancel()
			}
		})
		if id != "" {
			phase := "resolved"
			if err != nil || token == "" {
				phase = "unavailable"
			}
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				phase = "expired"
			} else if ctx.Err() != nil {
				phase = "cancelled"
			}
			broker.End(id, phase)
		}
		if presentErr != nil {
			return "", presentErr
		}
		return token, err
	}
}
