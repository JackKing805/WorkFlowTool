import tempfile
import unittest
from pathlib import Path

from PIL import Image

from seed_image_annotations import auto_instances_for_image, connected_components, seed_image_records


class SeedImageAnnotationsTest(unittest.TestCase):
    def test_transparent_background_icon_becomes_single_instance(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "icon.png"
            image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            for y in range(4, 12):
                for x in range(5, 11):
                    image.putpixel((x, y), (20, 30, 40, 255))
            image.save(image_path)

            instances = auto_instances_for_image(image_path)

            self.assertEqual(1, len(instances))
            self.assertEqual({"x": 5, "y": 4, "width": 6, "height": 8}, instances[0]["bbox"])
            self.assertEqual(48, sum(1 for value in instances[0]["alphaMask"] if value > 0))

    def test_solid_background_uses_color_distance(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "solid.png"
            image = Image.new("RGBA", (20, 20), (250, 250, 250, 255))
            for y in range(6, 14):
                for x in range(6, 14):
                    image.putpixel((x, y), (10, 80, 200, 255))
            image.save(image_path)

            instances = auto_instances_for_image(image_path)

            self.assertEqual(1, len(instances))
            self.assertEqual({"x": 6, "y": 6, "width": 8, "height": 8}, instances[0]["bbox"])

    def test_seed_records_ignore_non_images_and_tiny_noise(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text("ignored", "utf-8")
            noise = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
            noise.putpixel((3, 3), (255, 0, 0, 255))
            noise.save(root / "noise.png")
            icon = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
            for y in range(10, 22):
                for x in range(10, 22):
                    icon.putpixel((x, y), (0, 0, 0, 255))
            icon.save(root / "icon.png")

            records = seed_image_records(root)

            self.assertEqual(1, len(records))
            self.assertTrue(str(records[0]["image"]).endswith("icon.png"))

    def test_connected_components_track_bbox_during_scan(self):
        mask = [False] * 25
        for x, y in [(1, 1), (2, 1), (2, 2), (4, 4)]:
            mask[y * 5 + x] = True

        components = connected_components(mask, 5, 5)

        self.assertEqual(2, len(components))
        self.assertEqual((1, 1, 3, 3), (components[0].left, components[0].top, components[0].right, components[0].bottom))
        self.assertEqual((4, 4, 5, 5), (components[1].left, components[1].top, components[1].right, components[1].bottom))


if __name__ == "__main__":
    unittest.main()
