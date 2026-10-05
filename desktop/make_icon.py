"""ئایکۆنی .ico ی «SG search» بۆ exe دروست دەکات."""
from PIL import Image, ImageDraw, ImageFont

S = 1024
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
# ڕەنگی تێکەڵ (وەنەوشەیی → پەمەیی)
grad = Image.new("RGBA", (S, S))
a, b = (123, 92, 240), (240, 80, 143)
gd = ImageDraw.Draw(grad)
for i in range(2 * S):
    t = i / (2 * S - 1)
    c = tuple(int(a[k] + (b[k] - a[k]) * t) for k in range(3)) + (255,)
    gd.line([(i, 0), (0, i)], fill=c, width=2)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((32, 32, S - 32, S - 32), 240, fill=255)
im.paste(grad, (0, 0), mask)
d = ImageDraw.Draw(im)
font = None
for name in ("seguibl.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
    try:
        font = ImageFont.truetype(name, 470)
        break
    except OSError:
        pass
if font is None:
    font = ImageFont.load_default(size=470)
box = d.textbbox((0, 0), "SG", font=font)
w, h = box[2] - box[0], box[3] - box[1]
d.text(((S - w) / 2 - box[0], (S - h) / 2 - box[1] - 40), "SG", font=font, fill="white")
d.rounded_rectangle((312, 760, 712, 812), 26, fill=(255, 255, 255, 225))
im.save("icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
im.resize((512, 512), Image.LANCZOS).save("icon.png")
