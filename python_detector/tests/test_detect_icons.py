import numpy as np
import unittest

from detect_icons import build_foreground_mask


class DetectIconsThresholdTest(unittest.TestCase):
    def test_configured_threshold_keeps_obvious_foreground(self):
        probabilities = np.zeros((12, 12), dtype=np.float32)
        probabilities[3:7, 3:7] = 0.8

        mask, threshold, strategy = build_foreground_mask(probabilities, 0.28)

        self.assertEqual(16, int(mask.sum()))
        self.assertEqual(0.28, threshold)
        self.assertEqual("configured", strategy)

    def test_adaptive_threshold_recovers_low_confidence_foreground(self):
        probabilities = np.full((32, 32), 0.04, dtype=np.float32)
        probabilities[10:16, 11:17] = 0.16

        mask, threshold, strategy = build_foreground_mask(probabilities, 0.28)

        self.assertGreaterEqual(int(mask.sum()), 12)
        self.assertLess(threshold, 0.28)
        self.assertEqual("adaptive", strategy)

    def test_empty_low_contrast_map_stays_empty(self):
        probabilities = np.full((32, 32), 0.05, dtype=np.float32)

        mask, _, strategy = build_foreground_mask(probabilities, 0.28)

        self.assertEqual(0, int(mask.sum()))
        self.assertEqual("configured-empty", strategy)


if __name__ == "__main__":
    unittest.main()
