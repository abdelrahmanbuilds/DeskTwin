import unittest

from sync import phone_fs


class PhoneFileEventRevisionTests(unittest.TestCase):
    def test_revision_advances_after_recent_event_buffer_is_full(self):
        with phone_fs._lock:
            previous_events = list(phone_fs._events)
            previous_revision = phone_fs._event_revision
            phone_fs._events.clear()
            phone_fs._event_revision = 0

        try:
            for index in range(205):
                phone_fs.publish_event({"event": "changed", "index": index})

            self.assertEqual(len(phone_fs.recent_events()), 200)
            self.assertEqual(phone_fs.event_revision(), 205)
        finally:
            with phone_fs._lock:
                phone_fs._events[:] = previous_events
                phone_fs._event_revision = previous_revision


if __name__ == "__main__":
    unittest.main()
