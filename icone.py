# Disegna le icone di MassiStanze (una volta sola; i PNG/ICO sono nel repository)
from PIL import Image, ImageDraw
import os
def icona(n):
    S = 1024
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=220, fill=(16, 18, 24, 255))
    # casa
    d.polygon([(512, 170), (850, 470), (174, 470)], fill=(255, 179, 64, 255))
    d.rounded_rectangle([250, 440, 774, 860], radius=40, fill=(255, 179, 64, 255))
    # onde sonore nelle stanze
    for i, c in enumerate([(16, 18, 24), (16, 18, 24), (16, 18, 24)]):
        r = 90 + i * 85
        d.arc([512 - r, 650 - r, 512 + r, 650 + r], 215, 325, fill=c + (255,), width=46)
    d.ellipse([462, 600, 562, 700], fill=(16, 18, 24, 255))
    return im.resize((n, n), Image.LANCZOS)
for n in (192, 512):
    icona(n).save("web/icona-%d.png" % n)
for nome, n in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    icona(n).save("icone/android/%s.png" % nome)
icona(256).save("icone/massistanze.ico", sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
