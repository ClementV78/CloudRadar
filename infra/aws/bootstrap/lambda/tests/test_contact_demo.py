import importlib.util
import json
import os
from pathlib import Path
import unittest
from unittest.mock import MagicMock


class ContactDemoLambdaTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    os.environ["RATE_LIMIT_TABLE_NAME"] = "demo-rate-limit"
    os.environ["RECIPIENT_EMAIL"] = "demo@example.com"
    os.environ["SENDER_EMAIL"] = "noreply@example.com"
    os.environ["RATE_LIMIT_WINDOW_SECONDS"] = "900"
    os.environ["RATE_LIMIT_MAX_HITS"] = "3"

  def setUp(self):
    self.mock_ddb = MagicMock()
    self.mock_ses = MagicMock()
    module_path = Path(__file__).resolve().parents[1] / "contact_demo.py"
    spec = importlib.util.spec_from_file_location("contact_demo", module_path)
    assert spec is not None and spec.loader is not None
    self.module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(self.module)
    self.module._dynamodb = self.mock_ddb
    self.module._ses = self.mock_ses

  def tearDown(self):
    pass

  def _event(self, body):
    return {
      "requestContext": {"http": {"method": "POST", "sourceIp": "1.2.3.4"}},
      "body": json.dumps(body)
    }

  def test_accepts_valid_request(self):
    event = self._event(
      {
        "name": "Jane Doe",
        "email": "jane@example.com",
        "message": "I would like a live demo next week.",
        "honeypot": "",
        "page": "https://cloudradar.iotx.fr",
        "userAgent": "unit-test"
      }
    )

    response = self.module.handler(event, None)

    self.assertEqual(200, response["statusCode"])
    self.mock_ddb.update_item.assert_called_once()
    self.mock_ses.send_email.assert_called_once()

  def test_blocks_honeypot(self):
    event = self._event(
      {
        "name": "Jane Doe",
        "email": "jane@example.com",
        "message": "I would like a live demo next week.",
        "honeypot": "spam"
      }
    )

    response = self.module.handler(event, None)

    self.assertEqual(400, response["statusCode"])
    self.mock_ses.send_email.assert_not_called()

  def test_rejects_invalid_method(self):
    event = {
      "requestContext": {"http": {"method": "GET", "sourceIp": "1.2.3.4"}},
      "body": "{}"
    }

    response = self.module.handler(event, None)

    self.assertEqual(405, response["statusCode"])
    self.mock_ddb.update_item.assert_not_called()
    self.mock_ses.send_email.assert_not_called()

  def test_rejects_invalid_email(self):
    event = self._event(
      {
        "name": "Jane Doe",
        "email": "not-an-email",
        "message": "I would like a live demo next week.",
        "honeypot": ""
      }
    )

    response = self.module.handler(event, None)

    self.assertEqual(400, response["statusCode"])
    self.mock_ddb.update_item.assert_not_called()
    self.mock_ses.send_email.assert_not_called()


if __name__ == "__main__":
  unittest.main()
