// Compile le shader de FilmProgram.kt (variante sampler2D) avec WebGL dans Chromium headless.
// WebGL 1 = GLSL ES 1.00, le même langage que GLES 2 sur Android : une erreur ici est une
// erreur sur le téléphone (où elle ne se verrait qu'au lancement de l'appli).
//
// Usage : node tools/check_shader.js        (nécessite Playwright + Chromium)
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const src = fs.readFileSync(
  path.join(__dirname, '..', 'app/src/main/java/com/argenticam/FilmProgram.kt'), 'utf8');
const pick = (name) => {
  const m = src.match(new RegExp(name + ' = """([\\s\\S]*?)"""'));
  if (!m) throw new Error(name + ' introuvable dans FilmProgram.kt');
  return m[1];
};
const shaders = { v: pick('VERTEX'), f: pick('FRAGMENT').replace(/SAMPLER/g, 'sampler2D') };

(async () => {
  const browser = await chromium.launch({
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
  });
  const page = await browser.newPage();
  const res = await page.evaluate((sh) => {
    const gl = document.createElement('canvas').getContext('webgl');
    if (!gl) return { ok: false, log: 'WebGL indisponible' };
    const comp = (type, code) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, code);
      gl.compileShader(s);
      return [s, gl.getShaderParameter(s, gl.COMPILE_STATUS), gl.getShaderInfoLog(s)];
    };
    const [vs, vok, vlog] = comp(gl.VERTEX_SHADER, sh.v);
    const [fs, fok, flog] = comp(gl.FRAGMENT_SHADER, sh.f);
    const p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    const lok = gl.getProgramParameter(p, gl.LINK_STATUS);
    return { ok: vok && fok && lok, log: [vlog, flog, gl.getProgramInfoLog(p)].filter(Boolean).join('\n') };
  }, shaders);
  await browser.close();
  console.log(res.ok ? 'Shader OK' : 'Shader en erreur :\n' + res.log);
  process.exit(res.ok ? 0 : 1);
})();
