import subprocess
import unittest
from unittest.mock import patch

from tools import history_dev_link as link


class HistoryDevLinkTest(unittest.TestCase):
    def test_reverse_listing_requires_exact_port_pair(self):
        self.assertTrue(link.has_reverse("UsbFfs tcp:8000 tcp:8000\n"))
        self.assertFalse(link.has_reverse("UsbFfs tcp:8000 tcp:9000\n"))
        self.assertFalse(link.has_reverse("UsbFfs tcp:9000 tcp:8000\n"))

    def test_disconnected_device_does_not_change_reverse(self):
        disconnected = subprocess.CompletedProcess([], 1, "", "device offline")
        with patch.object(link, "adb_command", return_value=disconnected) as command:
            self.assertFalse(link.ensure_reverse("adb", "phone-1"))
        command.assert_called_once_with("adb", "phone-1", "get-state")

    def test_reconnect_repairs_only_target_reverse(self):
        ok = lambda output: subprocess.CompletedProcess([], 0, output, "")
        with patch.object(link, "adb_command", side_effect=[
            ok("device\n"), ok("UsbFfs tcp:3000 tcp:3000\n"), ok("8000\n"),
            ok("UsbFfs tcp:3000 tcp:3000\nUsbFfs tcp:8000 tcp:8000\n"),
        ]) as command:
            self.assertTrue(link.ensure_reverse("adb", "phone-1"))
        self.assertEqual(command.call_args_list[2].args, ("adb", "phone-1", "reverse", "tcp:8000", "tcp:8000"))

    def test_wireless_disconnect_reconnects_target_before_repair(self):
        ok = lambda output: subprocess.CompletedProcess([], 0, output, "")
        offline = subprocess.CompletedProcess([], 1, "", "offline")
        serial = "192.168.0.42:37123"
        with patch.object(link, "adb_command", side_effect=[offline, ok("device\n"), ok(""), ok("8000\n"), ok("UsbFfs tcp:8000 tcp:8000\n")]), \
                patch.object(link, "reconnect_wireless") as reconnect:
            self.assertTrue(link.ensure_reverse("adb", serial))
        reconnect.assert_called_once_with("adb", serial)

    def test_wireless_reconnect_accepts_only_ip_and_port(self):
        self.assertTrue(link.is_wireless_serial("192.168.0.42:37123"))
        self.assertFalse(link.is_wireless_serial("INPJH6FITS55T4OF"))
        self.assertFalse(link.is_wireless_serial("192.168.0.42:0"))
        self.assertFalse(link.is_wireless_serial("example.com:37123"))

    def test_uses_explicit_serial_and_keeps_adb_timeout_recoverable(self):
        with patch.object(link.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 12)) as run:
            result = link.adb_command("adb", "phone-1", "reverse", "--list")
        self.assertEqual(result.returncode, 124)
        self.assertEqual(run.call_args.args[0], ["adb", "-s", "phone-1", "reverse", "--list"])

    def test_only_local_history_endpoint_is_accepted(self):
        values = {"history.enabled": "true", "history.endpoint": "http://127.0.0.1:8000", "history.api.token": "x" * 16}
        self.assertEqual(link.validate_local_config(values), "x" * 16)
        for endpoint in ("http://remote.example:8000", "https://127.0.0.1:8000", "http://127.0.0.1:9000"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                link.validate_local_config({**values, "history.endpoint": endpoint})


if __name__ == "__main__":
    unittest.main()
