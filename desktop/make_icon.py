"""ئایکۆنی .ico بۆ exe دروست دەکات."""
from PIL import Image, ImageDraw
S = 256
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.rounded_rectangle((8, 8, 248, 248), 56, fill=(90, 90, 150, 255))
for y in range(150, 236, 14):
    for x in range(30, 226, 14):
        if (x // 14 + y // 14) % 2:
            d.rectangle((x, y, x + 13, y + 13), fill=(120, 120, 175, 255))
d.ellipse((88, 46, 168, 126), fill="white")
d.rounded_rectangle((58, 136, 198, 226), 60, fill="white")
im.save("icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
