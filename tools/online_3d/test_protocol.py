import struct
import unittest

from protocol import HEADER, MAGIC, MESSAGE_FRAME, VERSION, parse_frame, world_point


class ProtocolTest(unittest.TestCase):
    def test_parse_and_transform(self):
        matrix = (1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 4., 5., 6., 1.)
        header = HEADER.pack(MAGIC, VERSION, MESSAGE_FRAME, 1, 2, 3, 4, 5, 1,
                             1, 1, 10., 10., 0., 0., *matrix)
        frame = parse_frame(header + struct.pack(">H", 1000) + bytes([255]))
        self.assertEqual(2, frame.frame_id)
        self.assertTrue(frame.persistent_map)
        self.assertEqual((5., 7., 9.), world_point(frame.camera_to_world, (1., 2., 3.)))


if __name__ == "__main__":
    unittest.main()
