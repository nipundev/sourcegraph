package hubspot

import (
	"context"
	"net/url"
)

// LogEvent logs a user action or event. The response will have a status code of
// 200 with no data in the body
//
// http://developers.hubspot.com/docs/methods/enterprise_events/http_api
func (c *Client) LogEvent(ctx context.Context, email string, eventID string, params map[string]string) error {
	params["_a"] = c.portalID
	params["_n"] = eventID
	params["email"] = email
	err := c.get(ctx, "LogEvent", c.baseEventURL(), email, params)
	if err != nil {
		return err
	}
	return nil
}

func (c *Client) baseEventURL() *url.URL {
	return &url.URL{
		Scheme: "https",
		Host:   "track.hubspot.com",
		Path:   "/v1/event",
	}
}

// LogV3Event logs a user action or event. The response will have a status code of
// 204 with no data in the body
//
// https://developers.hubspot.com/docs/api/analytics/events
func (c *Client) LogV3Event(email, eventName string, properties any) error {
	params := V3EventParams{
		Email:      email,
		EventName:  eventName,
		Properties: properties}

	err := c.postJSON("LogV3Event", c.baseV3EventURL(), params, &struct{}{})
	if err != nil && err.Error() != "EOF" {
		return err
	}
	return nil
}

type V3EventParams struct {
	Email      string `json:"email"`
	EventName  string `json:"eventName"`
	Properties any    `json:"properties"`
}

type CodyInstallV3EventProperties struct {
	Ide           string `json:"ide"`
	EmailsEnabled string `json:"emails_enabled"`
}

func (c *Client) baseV3EventURL() *url.URL {
	return &url.URL{
		Scheme: "https",
		Host:   "api.hubspot.com",
		Path:   "/events/v3/send",
	}
}
