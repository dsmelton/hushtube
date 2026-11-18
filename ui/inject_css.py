#!/usr/bin/env python3
"""Re-inject the Hermes UI into docker-compose.yml's INVIDIOUS_CONFIG:
  banner: <- ui/banner.html + ui/ivx.css inlined + ui/VERSION  (rendered right after the navbar, before any
           page content: CSS + static shell (tab bar, top-bar icons, skeleton) + <script src=/js/ivx.js?v=N>)
  footer: <- ui/footer.html  (placeholder comment)
JS lives in ui/ivx.js (bind-mounted, served same-origin; bump ui/VERSION on every change). Run from
~/invidious-deploy, then:  podman-compose up -d --force-recreate --no-deps invidious"""
import os, re
COMPOSE = os.environ.get('COMPOSE', 'docker-compose.yml')   # repo layout: COMPOSE=compose/docker-compose.yml
def block(key, path):
    html = open(path).read().rstrip('\n')
    ind = '\n'.join(' ' * 10 + l.lstrip() if l.strip() else '' for l in html.split('\n'))
    return '        %s: |-\n%s\n' % (key, ind), len(html)
comp = open(COMPOSE).read()
# banner = ui/banner.html with the CSS inlined ({{CSS}}) and the ivx.js cache-buster ({{V}})
V = open('ui/VERSION').read().strip()
tpl = open('ui/banner.html').read().replace('{{CSS}}', open('ui/ivx.css').read().strip()).replace('{{V}}', V)
open('ui/.banner.built.html', 'w').write(tpl)
b, nb = block('banner', 'ui/.banner.built.html')
f, nf = block('footer', 'ui/footer.html')
# drop any existing banner block, then rewrite the footer block (footer is followed by `local: true`)
comp = re.sub(r'        banner: \|-\n(?:          .*\n|\n)*?(?=        \S)', '', comp)
start = comp.index('        footer: |-')
end = comp.index('        local: true')
comp = comp[:start] + b + f + comp[end:]
open(COMPOSE, 'w').write(comp)
print('injected banner %d bytes, footer %d bytes' % (nb, nf))
