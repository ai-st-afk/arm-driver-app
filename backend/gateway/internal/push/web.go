package push

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"

	"arm-driver-app/backend/gateway/internal/storage"
	webpush "github.com/SherClockHolmes/webpush-go"
)

type VAPIDKeys struct {
	Public  string `json:"public"`
	Private string `json:"private"`
}

func LoadOrCreateVAPID(dir string) (VAPIDKeys, error) {
	path := filepath.Join(dir, "web-vapid.json")
	if raw, err := os.ReadFile(path); err == nil {
		var keys VAPIDKeys
		if err := json.Unmarshal(raw, &keys); err != nil {
			return VAPIDKeys{}, err
		}
		if keys.Public == "" || keys.Private == "" {
			return VAPIDKeys{}, errors.New("web VAPID keys missing")
		}
		return keys, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return VAPIDKeys{}, err
	}
	private, public, err := webpush.GenerateVAPIDKeys()
	if err != nil {
		return VAPIDKeys{}, err
	}
	keys := VAPIDKeys{Public: public, Private: private}
	raw, err := json.Marshal(keys)
	if err != nil {
		return VAPIDKeys{}, err
	}
	if err := os.WriteFile(path+".tmp", raw, 0o600); err != nil {
		return VAPIDKeys{}, err
	}
	if err := os.Rename(path+".tmp", path); err != nil {
		return VAPIDKeys{}, err
	}
	return keys, nil
}

type WebSender struct {
	logger  *slog.Logger
	client  *http.Client
	store   *storage.Store
	keys    VAPIDKeys
	subject string
}

func NewWebSender(logger *slog.Logger, client *http.Client, store *storage.Store, keys VAPIDKeys, subject string) *WebSender {
	return &WebSender{logger: logger, client: client, store: store, keys: keys, subject: subject}
}

func (s *WebSender) SendAssignment(ctx context.Context, n AssignmentNotification) error {
	subscriptions, err := s.store.ListWebPush(n.DriverID)
	if err != nil {
		return err
	}
	if len(subscriptions) == 0 {
		return nil
	}
	payload, _ := json.Marshal(map[string]string{"title": n.Title(), "body": n.Body(), "assignment_id": n.AssignmentID})
	var failures []error
	for _, item := range subscriptions {
		response, err := webpush.SendNotificationWithContext(ctx, payload, &webpush.Subscription{
			Endpoint: item.Endpoint, Keys: webpush.Keys{Auth: item.Auth, P256dh: item.P256dh},
		}, &webpush.Options{HTTPClient: s.client, Subscriber: s.subject, TTL: 3600,
			VAPIDPublicKey: s.keys.Public, VAPIDPrivateKey: s.keys.Private})
		if err != nil {
			failures = append(failures, err)
			continue
		}
		response.Body.Close()
		if response.StatusCode == http.StatusGone || response.StatusCode == http.StatusNotFound {
			if err := s.store.RemoveWebPush(item.Endpoint); err != nil {
				failures = append(failures, err)
			}
			continue
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			failures = append(failures, fmt.Errorf("web push status %d", response.StatusCode))
		}
	}
	if len(failures) > 0 {
		s.logger.WarnContext(ctx, "web push delivery failed", "driver", n.DriverID, "count", len(failures))
	}
	return errors.Join(failures...)
}
