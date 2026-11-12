# Prepara la cartella www/ per l'app Android (stessa pagina, in modalità app).
import os
import shutil

QUI = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(QUI, "web")
WWW = os.path.join(QUI, "www")

shutil.rmtree(WWW, ignore_errors=True)
shutil.copytree(WEB, WWW)
p = os.path.join(WWW, "index.html")
html = open(p, encoding="utf-8").read()
assert "<!--APP-->" in html
html = html.replace("<!--APP-->", "<script>window.STANZE_APP = true;</script>")
open(p, "w", encoding="utf-8").write(html)
print("www pronta:", sorted(os.listdir(WWW)))
