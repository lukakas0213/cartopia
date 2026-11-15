/*
 * 카토피아 village page: the whole app UI, in one WebView, for both gears.
 *
 *   gear D  the car follows the drive and blocks open around it (drive card: 탐험 면적, 주행 거리, 안전 점수)
 *   gear P  the drive is saved, a summary card shows what it earned, and the map can be decorated
 *
 * three.js r128 + OrbitControls draw Seoul as pastel ground tiles (assets/map/seoul, tools/build_seoul.py).
 * Only blocks the player has driven are painted; the rest is sea. Items are built from primitives so they
 * fit the shape and size of the blocks they sit on. The game rules are written up in docs/game-rules.md.
 *
 * Talking to the app (Kotlin, see ui/VillageBridge.kt and core/AppCore.kt):
 *   page -> app   window.Cartopia: getInit, getMiles, earn, spend, saveState, takeTrips, startDemo
 *   app -> page   window.__cartopia({ t, ... }) with t = mode | car | reveal | trip | summary
 *                 window.__back() closes the open panel; returns false when there is nothing to close
 * Without the bridge (a plain browser) the page runs on its own with an empty map, for design work.
 *
 * Sections, in order:
 *   builders
 *   block geometry (village px -> world units)
 *   helpers
 *   data from the app
 *   map tiles
 *   catalog
 *   builders fitted to the block
 *   catalog thumbnails: each kind as a small diorama built from the same models as the map
 *   scene <-> state
 *   towers and landmarks
 *   revealed ground tiles
 *   selection highlight
 *   persistence
 *   growth from finished drives: everything placed or stored grows by the distance driven
 *   camera
 *   modes
 *   edit panels
 *   actions
 *   picking
 *   messages from the app
 *   resize + loop
 */
(async function () {
  const fail = () => { document.getElementById('fail').hidden = false; };
  if (!window.THREE || !THREE.OrbitControls) { fail(); return; }
  // Seoul in square tiles (tools/build_seoul.py): index + the blocks larger than a tile, the rest on demand
  const getJSON = (f) => fetch('../map/seoul/' + f).then((r) => { if (!r.ok) throw new Error(f); return r.json(); });
  let mapIndex, bigBlocks;
  try { [mapIndex, bigBlocks] = await Promise.all([getJSON('index.json'), getJSON('big.json')]); } catch (e) { fail(); return; }
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const stage = document.getElementById('stage');

  // map pixel -> world; 10 px = 1 unit. (0,0)-(1280,720) is the old 응봉·성수 view; Seoul spreads around it
  const W = (x, y) => new THREE.Vector3(x / 10 - 64, 0, y / 10 - 36);

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true });
  } catch (e) { fail(); return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.outputEncoding = THREE.sRGBEncoding;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  stage.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  // soft daytime sky: a vertical gradient behind everything
  const sky = document.createElement('canvas');
  sky.width = 2; sky.height = 256;
  const sg = sky.getContext('2d'), grad = sg.createLinearGradient(0, 0, 0, 256);
  grad.addColorStop(0, '#8FD3F4'); grad.addColorStop(0.6, '#CBEFFA'); grad.addColorStop(1, '#FFF4DA');
  sg.fillStyle = grad; sg.fillRect(0, 0, 2, 256);
  scene.background = new THREE.CanvasTexture(sky);
  scene.background.encoding = THREE.sRGBEncoding;
  scene.fog = new THREE.Fog('#BEE5F2', 90, 190);

  const camera = new THREE.PerspectiveCamera(38, 1, 0.5, 400);
  camera.position.set(4, 72, 70);
  const controls = new THREE.OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.maxPolarAngle = 1.15;
  controls.minPolarAngle = 0.35;
  controls.minDistance = 10;
  controls.maxDistance = 260; // the whole-map view after a drive steps back this far
  controls.screenSpacePanning = false;
  controls.target.set(0, 0, 0);

  // lights
  scene.add(new THREE.HemisphereLight(0xe6f6ff, 0xffe6cc, 0.5));
  const sun = new THREE.DirectionalLight(0xfff0d6, 0.5);
  sun.position.set(-30, 60, 25);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -70, right: 70, top: 45, bottom: -45, near: 1, far: 160 });
  sun.shadow.bias = -0.0008;
  sun.shadow.radius = 4;
  scene.add(sun);
  scene.add(sun.target); // the sun and its shadow box follow the camera target across the city
  const SUN_OFF = new THREE.Vector3(-30, 60, 25);

  // ground: unrevealed Seoul is pastel sea; each map tile paints its revealed blocks onto its own canvas
  const sea = new THREE.Mesh(new THREE.PlaneGeometry(900, 900), new THREE.MeshBasicMaterial({ color: '#BEE5F2' }));
  sea.rotation.x = -Math.PI / 2;
  sea.position.y = -0.05;
  scene.add(sea);
  const shadowCatcher = new THREE.Mesh(new THREE.PlaneGeometry(240, 240), new THREE.ShadowMaterial({ opacity: 0.18, color: new THREE.Color('#6A4FA0') }));
  shadowCatcher.rotation.x = -Math.PI / 2;
  shadowCatcher.position.y = 0.02;
  shadowCatcher.receiveShadow = true;
  scene.add(shadowCatcher);

  // toon materials
  const ramp = document.createElement('canvas');
  ramp.width = 4; ramp.height = 1;
  const rc = ramp.getContext('2d');
  ['#b4a8cc', '#d9d1e8', '#f4f0f8', '#ffffff'].forEach((c, i) => { rc.fillStyle = c; rc.fillRect(i, 0, 1, 1); });
  const gradientMap = new THREE.CanvasTexture(ramp);
  gradientMap.minFilter = gradientMap.magFilter = THREE.NearestFilter;
  const mats = {};
  const toon = (hex) => mats[hex] || (mats[hex] = new THREE.MeshToonMaterial({ color: hex, gradientMap }));

  function mesh(geo, color, opts) {
    const m = new THREE.Mesh(geo, typeof color === 'string' ? toon(color) : color);
    m.castShadow = true;
    m.receiveShadow = true;
    if (opts) {
      if (opts.p) m.position.set(...opts.p);
      if (opts.r) m.rotation.set(...opts.r);
      if (opts.s) m.scale.set(...opts.s);
    }
    return m;
  }
  const sph = (r, seg = 20) => new THREE.SphereGeometry(r, seg, Math.round(seg * 0.75));
  const cyl = (rt, rb, h, seg = 16) => new THREE.CylinderGeometry(rt, rb, h, seg);
  const box = (x, y, z) => new THREE.BoxGeometry(x, y, z);

  // ---------- builders ----------
  // big glossy eyes + blush, the Animal Crossing style face; x points forward
  function face(head, x, y, z, s = 1, blush = true) {
    [-1, 1].forEach((sd) => {
      head.add(mesh(sph(0.075 * s, 12), '#2B2230', { p: [x, y, sd * z], s: [0.6, 1.25, 1] }));
      const hi = mesh(sph(0.026 * s, 8), '#FFFFFF', { p: [x + 0.035 * s, y + 0.04 * s, sd * z + 0.012 * s] });
      hi.castShadow = false; head.add(hi);
      if (blush) {
        const b = mesh(sph(0.06 * s, 10), '#FF9DB3', { p: [x - 0.02 * s, y - 0.1 * s, sd * (z + 0.07 * s)], s: [0.35, 0.6, 1] });
        b.castShadow = false; head.add(b);
      }
    });
  }
  function pad(r, color = '#86C26A', h = 0.5) {
    const g = new THREE.Group();
    g.add(mesh(cyl(r, r + 0.25, h, 48), color, { p: [0, h / 2, 0] }));
    g.add(mesh(cyl(r + 0.25, r + 0.45, 0.25, 48), '#7A5A3E', { p: [0, 0.12, 0] }));
    return g;
  }

  function roundTree(kind) {
    const g = new THREE.Group();
    const crownColor = kind === 'cherry' ? '#FF9EC4' : kind === 'apple' ? '#4FCB6A' : '#5ED47A';
    g.add(mesh(cyl(0.22, 0.3, 1.4, 10), '#B07040', { p: [0, 0.7, 0] }));
    const crown = new THREE.Group();
    crown.position.y = 1.9;
    [[0, 0.3, 0, 1.0], [0.55, 0, 0.2, 0.75], [-0.5, 0.05, -0.15, 0.78], [0.05, -0.1, 0.55, 0.7], [-0.1, 0.75, -0.1, 0.6]]
      .forEach(([x, y, z, r]) => crown.add(mesh(sph(r, 16), crownColor, { p: [x, y, z] })));
    if (kind === 'apple') [[0.6, 0.2, 0.55], [-0.45, 0.45, 0.6], [0.2, 0.9, 0.5], [-0.7, -0.05, 0.3]]
      .forEach((p) => crown.add(mesh(sph(0.17, 10), '#FF5A4E', { p })));
    g.add(crown);
    g.userData.sway = crown;
    return g;
  }

  function pine() {
    const g = new THREE.Group();
    g.add(mesh(cyl(0.18, 0.24, 0.9, 8), '#A86A3C', { p: [0, 0.45, 0] }));
    const crown = new THREE.Group();
    [[1.1, 1.3, 1.2], [0.85, 1.1, 1.9], [0.55, 0.9, 2.55]].forEach(([r, h, y]) => crown.add(mesh(new THREE.ConeGeometry(r, h, 9), '#36C08A', { p: [0, y, 0] })));
    g.add(crown);
    g.userData.sway = crown;
    return g;
  }

  function sheep() {
    const g = new THREE.Group();
    const body = new THREE.Group();
    body.position.y = 0.95;
    [[0, 0.15, 0, 0.55], [0.4, 0.05, 0.22, 0.45], [0.4, 0.05, -0.22, 0.45], [-0.4, 0.05, 0.22, 0.45], [-0.4, 0.05, -0.22, 0.45], [0, 0.42, 0, 0.42], [-0.62, 0.15, 0, 0.36]]
      .forEach(([x, y, z, r]) => body.add(mesh(sph(r, 14), '#FFFFFF', { p: [x, y, z] })));
    const head = new THREE.Group();
    head.position.set(0.72, 0.28, 0);
    head.add(mesh(sph(0.38, 18), '#FFE3CF', { s: [1.0, 1.0, 1.05] }));
    [[-0.12, 0.3, 0, 0.24], [-0.02, 0.32, 0.17, 0.17], [-0.02, 0.32, -0.17, 0.17]]
      .forEach(([x, y, z, r]) => head.add(mesh(sph(r, 12), '#FFFFFF', { p: [x, y, z] })));
    [-1, 1].forEach((sd) => head.add(mesh(sph(0.13, 10), '#FFC9B0', { p: [-0.05, 0.08, sd * 0.4], s: [1.4, 0.55, 0.9], r: [sd * 0.4, 0, 0] })));
    face(head, 0.33, 0.05, 0.13, 1.1);
    head.add(mesh(sph(0.04, 8), '#E98A8A', { p: [0.37, -0.1, 0] }));
    body.add(head);
    g.add(body);
    const legs = [];
    [[0.32, 0.2], [0.32, -0.2], [-0.32, 0.2], [-0.32, -0.2]].forEach(([x, z]) => {
      const l = mesh(cyl(0.09, 0.08, 0.6, 8), '#FFE3CF', { p: [x, 0.3, z] });
      legs.push(l); g.add(l);
    });
    g.userData = { body, head, legs };
    return g;
  }

  function deer(fawn) {
    const c = fawn ? '#F2B57A' : '#E39A5C';
    const g = new THREE.Group();
    const body = new THREE.Group();
    body.position.y = 1.15;
    body.add(mesh(sph(0.55, 18), c, { s: [1.35, 0.8, 0.75] }));
    body.add(mesh(sph(0.4, 14), '#F1E2CC', { p: [0, -0.15, 0], s: [1.4, 0.6, 0.75] }));
    [[0.2, 0.35, 0.25], [-0.25, 0.32, 0.28], [0.45, 0.25, 0.3], [-0.05, 0.4, -0.3], [-0.4, 0.3, -0.27], [0.3, 0.33, -0.3]]
      .forEach((p) => body.add(mesh(sph(0.07, 8), '#FFF8EC', { p })));
    const neck = mesh(cyl(0.16, 0.22, 0.75, 10), c, { p: [0.62, 0.42, 0], r: [0, 0, -0.5] });
    body.add(neck);
    const head = new THREE.Group();
    head.position.set(0.88, 0.85, 0);
    head.add(mesh(sph(0.27, 14), c, { s: [1.3, 1, 0.95] }));
    head.add(mesh(sph(0.1, 8), '#3B2A20', { p: [0.36, -0.04, 0] }));
    [-1, 1].forEach((sd) => {
      head.add(mesh(sph(0.12, 10), c, { p: [-0.1, 0.2, sd * 0.24], s: [0.6, 1.2, 0.5], r: [sd * 0.5, 0, 0] }));

      if (!fawn) {
        const a = new THREE.Group();
        a.position.set(-0.02, 0.24, sd * 0.1);
        a.rotation.x = sd * 0.35;
        a.add(mesh(cyl(0.03, 0.04, 0.55, 6), '#E8D9C4', { p: [0, 0.27, 0] }));
        a.add(mesh(cyl(0.025, 0.03, 0.28, 6), '#E8D9C4', { p: [0.1, 0.35, 0], r: [0, 0, -0.8] }));
        a.add(mesh(cyl(0.025, 0.03, 0.22, 6), '#E8D9C4', { p: [-0.07, 0.5, 0], r: [0, 0, 0.7] }));
        head.add(a);
      }
    });
    body.add(head);
    face(head, 0.27, 0.08, 0.15, 1);
    body.add(mesh(sph(0.12, 8), '#FFF8EC', { p: [-0.75, 0.15, 0] }));
    g.add(body);
    const legs = [];
    [[0.45, 0.2], [0.45, -0.2], [-0.45, 0.2], [-0.45, -0.2]].forEach(([x, z]) => {
      const l = mesh(cyl(0.07, 0.055, 0.85, 8), '#B87445', { p: [x, 0.43, z] });
      legs.push(l); g.add(l);
    });
    if (fawn) g.scale.setScalar(0.65);
    g.userData = { body, head, legs };
    return g;
  }

  function fenceRing(rx, rz, n) {
    const g = new THREE.Group();
    const pts = [];
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2;
      const p = new THREE.Vector3(Math.cos(a) * rx, 0, Math.sin(a) * rz);
      pts.push(p);
      g.add(mesh(cyl(0.11, 0.12, 0.95, 8), '#F2E6D2', { p: [p.x, 0.97, p.z] }));
      g.add(mesh(new THREE.ConeGeometry(0.13, 0.18, 8), '#F2E6D2', { p: [p.x, 1.53, p.z] }));
    }
    pts.forEach((a, i) => {
      const b = pts[(i + 1) % n];
      if (i === 1) return; // gate gap
      const len = a.distanceTo(b);
      const ang = -Math.atan2(b.z - a.z, b.x - a.x);
      [0.95, 1.3].forEach((y) => g.add(mesh(box(len, 0.1, 0.07), '#F2E6D2', { p: [(a.x + b.x) / 2, y, (a.z + b.z) / 2], r: [0, ang, 0] })));
    });
    return g;
  }

  function gableRoof(w, d, h, color) {
    const s = new THREE.Shape();
    s.moveTo(-w / 2, 0); s.lineTo(w / 2, 0); s.lineTo(0, h); s.lineTo(-w / 2, 0);
    const geo = new THREE.ExtrudeGeometry(s, { depth: d, bevelEnabled: true, bevelThickness: 0.08, bevelSize: 0.08, bevelSegments: 2 });
    geo.translate(0, 0, -d / 2);
    return mesh(geo, color);
  }

  function barn() {
    const g = new THREE.Group();
    g.add(mesh(box(3.2, 2.2, 2.6), '#FF6F5B', { p: [0, 1.1 + 0.5, 0] }));
    const roof = gableRoof(3.7, 2.9, 1.4, '#FFF4E2');
    roof.position.y = 2.7 + 0.5;
    g.add(roof);
    g.add(mesh(box(1.2, 1.4, 0.08), '#FFF6E8', { p: [0, 1.2, 1.32] }));
    g.add(mesh(box(1.0, 1.2, 0.06), '#E5533F', { p: [0, 1.2, 1.35] }));
    g.add(mesh(box(0.08, 1.5, 0.05), '#FFF6E8', { p: [0, 1.2, 1.39], r: [0, 0, 0.7] }));
    g.add(mesh(box(0.08, 1.5, 0.05), '#FFF6E8', { p: [0, 1.2, 1.39], r: [0, 0, -0.7] }));
    g.add(mesh(cyl(0.45, 0.45, 2.6, 16), '#E9E2D6', { p: [2.1, 1.8, -0.5] }));
    g.add(mesh(sph(0.47, 16), '#7CC8F0', { p: [2.1, 3.1, -0.5], s: [1, 0.6, 1] }));
    return g;
  }

  function house(roofColor = '#5BB8F5', wall = '#FFF6E6') {
    const g = new THREE.Group();
    const base = 0.5;
    g.add(mesh(box(3.4, 2.4, 2.8), wall, { p: [0, base + 1.2, 0] }));
    const roof = gableRoof(4.0, 3.2, 1.6, roofColor);
    roof.position.y = base + 2.4;
    g.add(roof);
    g.add(mesh(box(0.5, 1.1, 0.5), '#F28B6B', { p: [0.95, base + 3.6, -0.5] }));
    g.add(mesh(box(0.8, 1.4, 0.1), '#FF9E6B', { p: [0, base + 0.7, 1.42] }));
    g.add(mesh(sph(0.06, 8), '#FFD25E', { p: [0.25, base + 0.75, 1.5] }));
    const glow = new THREE.MeshBasicMaterial({ color: '#FFD98A' });
    const windows = [];
    [-1.05, 1.05].forEach((x) => {
      const w = mesh(box(0.75, 0.7, 0.08), glow, { p: [x, base + 1.45, 1.42] });
      w.castShadow = false;
      windows.push(w); g.add(w);
      g.add(mesh(box(0.95, 0.12, 0.25), '#8A5A35', { p: [x, base + 1.02, 1.5] }));
      [-0.3, 0, 0.3].forEach((dx, i) => g.add(mesh(sph(0.12, 8), i % 2 ? '#FFD25E' : '#F28FB0', { p: [x + dx, base + 1.18, 1.5] })));
    });
    g.add(mesh(box(1.2, 0.18, 0.6), '#C9B79C', { p: [0, base + 0.09, 1.75] }));
    const lamp = new THREE.PointLight(0xffc870, 0.4, 5);
    lamp.position.set(0, base + 1.5, 2.2);
    g.add(lamp);
    g.userData = { windows, glow, lamp };
    return g;
  }

  function mailbox() {
    const g = new THREE.Group();
    g.add(mesh(cyl(0.07, 0.07, 1.0, 8), '#8A5A35', { p: [0, 0.5, 0] }));
    g.add(mesh(box(0.55, 0.4, 0.35), '#E8473A', { p: [0, 1.15, 0] }));
    g.add(mesh(cyl(0.2, 0.2, 0.55, 12, 1), '#E8473A', { p: [0, 1.35, 0], r: [0, 0, Math.PI / 2] }));
    g.add(mesh(box(0.05, 0.3, 0.1), '#FFD25E', { p: [0.3, 1.45, 0.1] }));
    return g;
  }

  function dock() {
    const g = new THREE.Group();
    for (let i = 0; i < 6; i++) g.add(mesh(box(0.55, 0.14, 2.2), i % 2 ? '#B9824F' : '#A87444', { p: [i * 0.6 - 1.5, 0.75, 0] }));
    [[-1.7, 1], [-1.7, -1], [1.7, 1], [1.7, -1]].forEach(([x, z]) => g.add(mesh(cyl(0.12, 0.12, 1.2, 8), '#7A4E2E', { p: [x, 0.5, z] })));
    const rod = mesh(cyl(0.03, 0.04, 2.6, 6), '#3B2A20', { p: [2.4, 1.9, 0.4], r: [0, 0, -0.9] });
    g.add(rod);
    const bobber = new THREE.Group();
    bobber.position.set(4.3, 0.15, 0.4);
    bobber.add(mesh(sph(0.14, 10), '#E8473A'));
    bobber.add(mesh(sph(0.1, 10), '#FFFFFF', { p: [0, 0.1, 0] }));
    g.add(bobber);
    const ripple = new THREE.Mesh(new THREE.TorusGeometry(0.4, 0.035, 6, 32), new THREE.MeshBasicMaterial({ color: '#FFFFFF', transparent: true, opacity: 0.6 }));
    ripple.rotation.x = Math.PI / 2;
    ripple.position.set(4.3, 0.08, 0.4);
    g.add(ripple);
    const boat = new THREE.Group();
    boat.position.set(-0.6, 0.25, 2.3);
    const hull = new THREE.SphereGeometry(0.8, 18, 10, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2);
    boat.add(mesh(hull, '#FFD54A', { s: [1.7, 0.6, 0.85] }));
    boat.add(mesh(box(1.8, 0.08, 0.9), '#B9824F', { p: [0, 0.02, 0] }));
    g.add(boat);
    g.userData = { bobber, ripple, boat };
    return g;
  }

  // ---------- block geometry (village px -> world units) ----------
  let seed = 7;
  const rnd = () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const toW = (pts) => pts.map(([x, y]) => [x / 10 - 64, y / 10 - 36]);
  function area(a) {
    const outer = toW(a.outer), holes = (a.holes || []).map(toW);
    const xs = outer.map((p) => p[0]), zs = outer.map((p) => p[1]);
    return { outer, holes, size: (a.area || 0) / 100, min: [Math.min(...xs), Math.min(...zs)], max: [Math.max(...xs), Math.max(...zs)] };
  }
  function inRing(r, x, z) {
    let c = false;
    for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
      const [xi, zi] = r[i], [xj, zj] = r[j];
      if ((zi > z) !== (zj > z) && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) c = !c;
    }
    return c;
  }
  // a selection of several blocks is one area made of parts; a single block is its own only part
  const partsOf = (A) => A.parts || [A];
  const inside = (A, x, z) => partsOf(A).some((P) => inRing(P.outer, x, z) && !P.holes.some((h) => inRing(h, x, z)));
  const partAt = (A, x, z) => partsOf(A).find((P) => inside(P, x, z)) || partsOf(A)[0];
  function areaOfMany(list) {
    if (list.length === 1) return list[0];
    const big = list.reduce((m, P) => (P.size > m.size ? P : m));
    return { parts: list, outer: big.outer, holes: [], size: list.reduce((t, P) => t + P.size, 0), min: [Math.min(...list.map((P) => P.min[0])), Math.min(...list.map((P) => P.min[1]))], max: [Math.max(...list.map((P) => P.max[0])), Math.max(...list.map((P) => P.max[1]))] };
  }
  function edgeDist(A, x, z) {
    let best = Infinity;
    partsOf(A).flatMap((P) => [P.outer, ...P.holes]).forEach((r) => r.forEach((a, i) => {
      const b = r[(i + 1) % r.length], dx = b[0] - a[0], dz = b[1] - a[1], L = dx * dx + dz * dz;
      const t = L ? Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / L)) : 0;
      best = Math.min(best, Math.hypot(x - a[0] - t * dx, z - a[1] - t * dz));
    }));
    return best;
  }
  // the roomiest point in the block and how much room it has
  function pole(A, avoid = []) {
    let best = { x: 0, z: 0, r: -1 };
    partsOf(A).forEach((P) => {
      const step = Math.max(0.25, (P.max[0] - P.min[0]) / 60);
      for (let x = P.min[0]; x <= P.max[0]; x += step) for (let z = P.min[1]; z <= P.max[1]; z += step) {
        if (!inside(P, x, z)) continue;
        let r = edgeDist(P, x, z);
        avoid.forEach((o) => { r = Math.min(r, Math.hypot(x - o.x, z - o.z) - o.r); });
        if (r > best.r) best = { x, z, r };
      }
    });
    return best;
  }
  function randomInside(A, margin, taken = [], gap = 0) {
    const parts = partsOf(A), total = parts.reduce((t, P) => t + P.size, 0);
    for (let k = 0; k < 400; k++) {
      let pick = rnd() * total, P = parts[0];
      for (const Q of parts) { P = Q; if ((pick -= Q.size) <= 0) break; } // bigger blocks get more of the things
      const x = P.min[0] + rnd() * (P.max[0] - P.min[0]), z = P.min[1] + rnd() * (P.max[1] - P.min[1]);
      if (inside(P, x, z) && edgeDist(P, x, z) > margin && taken.every((o) => Math.hypot(x - o.x, z - o.z) > gap + (o.r || 0))) return { x, z };
    }
    return null;
  }
  function longestEdgeAngle(A) {
    let best = 0, ang = 0;
    A.outer.forEach((a, i) => { const b = A.outer[(i + 1) % A.outer.length], L = Math.hypot(b[0] - a[0], b[1] - a[1]); if (L > best) { best = L; ang = Math.atan2(b[1] - a[1], b[0] - a[0]); } });
    return -ang;
  }
  const ISLAND_H = 0.5;
  function island(A, color) {
    if (A.parts) { const g = new THREE.Group(); A.parts.forEach((P) => g.add(island(P, color))); return g; }
    const s = new THREE.Shape(A.outer.map(([x, z]) => new THREE.Vector2(x, -z)));
    A.holes.forEach((h) => s.holes.push(new THREE.Path(h.map(([x, z]) => new THREE.Vector2(x, -z)))));
    const geo = new THREE.ExtrudeGeometry(s, { depth: ISLAND_H, bevelEnabled: true, bevelThickness: 0.12, bevelSize: 0.18, bevelSegments: 3 });
    geo.rotateX(-Math.PI / 2);
    const g = new THREE.Group();
    g.add(mesh(geo, color));
    const soil = new THREE.ExtrudeGeometry(s, { depth: 0.22, bevelEnabled: false });
    soil.rotateX(-Math.PI / 2);
    g.add(mesh(soil, '#E2A86E', { s: [1, 1, 1] }));
    return g;
  }
  function fenceAlong(A, inset, spacing) {
    if (A.parts) { const g = new THREE.Group(); A.parts.forEach((P) => g.add(fenceAlong(P, inset, spacing))); return g; }
    const g = new THREE.Group();
    const c = pole(A);
    const pts = [];
    A.outer.forEach((a, i) => {
      const b = A.outer[(i + 1) % A.outer.length];
      const n = Math.max(1, Math.round(Math.hypot(b[0] - a[0], b[1] - a[1]) / spacing));
      for (let k = 0; k < n; k++) {
        let x = a[0] + (b[0] - a[0]) * (k / n), z = a[1] + (b[1] - a[1]) * (k / n);
        const dx = c.x - x, dz = c.z - z, L = Math.hypot(dx, dz) || 1;
        x += (dx / L) * inset; z += (dz / L) * inset;
        if (inside(A, x, z)) pts.push(new THREE.Vector3(x, ISLAND_H, z));
      }
    });
    pts.forEach((p, i) => {
      g.add(mesh(cyl(0.1, 0.11, 0.9, 8), '#F2E6D2', { p: [p.x, p.y + 0.45, p.z] }));
      g.add(mesh(new THREE.ConeGeometry(0.12, 0.16, 8), '#F2E6D2', { p: [p.x, p.y + 0.98, p.z] }));
      const q = pts[(i + 1) % pts.length];
      const len = p.distanceTo(q);
      if (i === 0 || len > spacing * 2.2) return; // leave a gate, skip long jumps across concave gaps
      const ang = -Math.atan2(q.z - p.z, q.x - p.x);
      [0.45, 0.78].forEach((y) => g.add(mesh(box(len, 0.09, 0.06), '#F2E6D2', { p: [(p.x + q.x) / 2, p.y + y, (p.z + q.z) / 2], r: [0, ang, 0] })));
    });
    return g;
  }

  // ---------- helpers ----------
  const $ = (id) => document.getElementById(id);
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const fmt = (n) => Number(n).toLocaleString('ko-KR');
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const svg = (d, size = 22, sw = 2.2) => `<svg aria-hidden="true" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="${d}"/></svg>`;
  const ICON = {
    tree: 'M12 3a5 5 0 0 0-5 5 4 4 0 0 0 1 7h8a4 4 0 0 0 1-7 5 5 0 0 0-5-5zM12 15v6',
    flower: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 3v4M12 17v4M3 12h4M17 12h4',
    pond: 'M3 14c3-3 6 3 9 0s6 3 9 0M3 18c3-3 6 3 9 0s6 3 9 0M7 9c2-2 4 2 6 0',
    fence: 'M4 20V9M10 20V9M16 20V9M2 12h20M2 17h20',
    deer: 'M7 3v4l3 2M17 3v4l-3 2M8 11h8l-1 9H9z',
    house: 'M3 11l9-7 9 7v9H3zM10 20v-6h4v6',
    cafe: 'M4 8h12v6a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5zM16 10h2a2 2 0 0 1 0 4h-2',
    bread: 'M4 14a8 6 0 0 1 16 0v4H4z',
    fish: 'M3 12c4-6 11-6 15 0-4 6-11 6-15 0zM18 12l3-3v6z',
    plus: 'M12 5v14M5 12h14',
    move: 'M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20',
    shuffle: 'M20 12a8 8 0 1 1-3-6.2M20 4v5h-5',
    box: 'M3 7h18v13H3zM3 7l2-4h14l2 4M10 12h4',
    check: 'M5 12l5 5L20 7',
    close: 'M6 6l12 12M18 6L6 18',
    up: 'M12 19V5M5 12l7-7 7 7',
    image: 'M3 5h18v14H3zM3 15l5-4 4 3 3-2 6 4',
  };

  // ---------- data from the app ----------
  const bridge = window.Cartopia || null;
  // ---------- map tiles ----------
  const TILE = mapIndex.tile, TW = TILE / 10;
  const blocks = new Map(bigBlocks.blocks.map((b) => [b.id, b]));
  const tileKey = (tx, ty) => `${tx}_${ty}`;
  const tileOfId = (id) => { const k = Math.floor(id / 8192); return k ? tileKey((k % 128) - 64, Math.floor(k / 128) - 64) : null; };
  const tileData = new Map(); // key -> Promise of the tile json, kept once read
  function loadTile(key) {
    if (!(key in mapIndex.tiles)) return Promise.resolve(null);
    if (!tileData.has(key)) {
      tileData.set(key, getJSON(`t/${key}.json`).then((d) => { d.blocks.forEach((b) => blocks.set(b.id, b)); return d; }).catch(() => null));
    }
    return tileData.get(key);
  }
  const loadBlocks = (ids) => Promise.all([...new Set(ids.map(tileOfId).filter(Boolean))].map(loadTile));

  const init = bridge ? JSON.parse(bridge.getInit())
    : { mode: 'edit', vehicle: false, miles: 1302, revealed: [], state: '{}' };
  if (!bridge) { const d = await loadTile('0_0'); init.revealed = d.blocks.filter((b) => b.isle).slice(0, 90).map((b) => b.id); }
  const revealed = new Set(init.revealed);
  const fresh = () => ({ v: 2, items: [], inventory: [], nextId: 1 });
  let S;
  try { S = JSON.parse(init.state || '{}'); } catch (e) { S = {}; }
  if (S.v !== 2) S = fresh(); // the first prototype's state (wool, dex) has no blocks; start the village over
  S.items.forEach((it) => { if (!it.blocks) { it.blocks = [it.block]; delete it.block; } }); // items once sat on one block only
  // rules v1 had item levels 1..5 and stored products; v2 grows items by distance (docs/game-rules.md)
  S.items.concat(S.inventory).forEach((it) => {
    if (it.km == null) it.km = it.lv >= 4 ? 100 : it.lv >= 2 ? 30 : 0;
    delete it.lv; delete it.stock; delete it.carry; delete it.name;
  });

  let miles = init.miles;
  const refreshMiles = () => { if (bridge) miles = Number(bridge.getMiles()); $('milesN').textContent = fmt(miles); };
  const spend = (n) => { if (n <= 0) return true; const ok = bridge ? bridge.spend(n) : miles >= n; if (ok && !bridge) miles -= n; refreshMiles(); return ok; };
  const earn = (n) => { if (n <= 0) return; if (bridge) bridge.earn(n); else miles += n; refreshMiles(); };
  const applyMiles = (d) => (d < 0 ? spend(-d) : (earn(d), true));

  // ---------- catalog ----------
  const KINDS = {
    trees: { cat: 'nature', name: '숲', price: 40, icon: 'tree', lands: ['forest', 'park', 'farm', 'res', 'civic'], best: ['forest'] },
    flowers: { cat: 'nature', name: '꽃밭', price: 30, icon: 'flower', lands: ['forest', 'park', 'farm', 'res', 'com', 'ind', 'civic'], best: ['park'] },
    pond: { cat: 'nature', name: '연못', price: 80, icon: 'pond', lands: ['forest', 'park', 'farm', 'res', 'civic', 'water'], best: ['park'] },
    farm: { cat: 'animal', name: '양 목장', price: 150, icon: 'fence', lands: ['forest', 'park', 'farm'], best: ['farm', 'park'] },
    deer: { cat: 'animal', name: '사슴 쉼터', price: 120, icon: 'deer', lands: ['forest', 'park'], best: ['forest'] },
    home: { cat: 'building', name: '내 집', price: 0, icon: 'house', max: 1, lands: ['forest', 'farm', 'res', 'com'], best: ['res'] },
    cafe: { cat: 'building', name: '카페', price: 120, icon: 'cafe', lands: ['park', 'res', 'com', 'ind', 'civic'], best: ['com'] },
    bakery: { cat: 'building', name: '빵집', price: 120, icon: 'bread', lands: ['res', 'com', 'ind', 'civic'], best: ['com'] },
    fishing: { cat: 'water', name: '낚시터', price: 100, icon: 'fish', water: true, lands: ['forest', 'park', 'farm', 'res', 'com', 'ind', 'civic', 'water'], best: ['water', 'park'] },
  };
  const CATS = [['rec', '추천'], ['nature', '자연'], ['animal', '동물'], ['building', '건물'], ['water', '강가'], ['inv', '보관함']];
  // a kind opens once a block of this terrain has been revealed anywhere (shore = a block touching the river or sea)
  const UNLOCK = { pond: 'park', farm: 'park', deer: 'forest', cafe: 'com', bakery: 'com', fishing: 'shore' };
  const FOUND_NAME = { park: '공원', forest: '산림', com: '상업지', shore: '강가' };
  // growth: km driven since the item was placed; stage 2 from 30 km, stage 3 from 100 km
  const STAGES = [0, 30, 100], GROW_K = [0.7, 1, 1.3];
  const tierOf = (b) => (b.areaM2 < 5000 ? { name: '소형', mult: 1 } : b.areaM2 < 30000 ? { name: '중형', mult: 1.5 } : { name: '대형', mult: 2 });
  const isGreen = (b) => b.green > 0.35;
  // terrain types (지형) from tools/classify_land.py, after the land cover map's 대분류 with built-up land split by zoning
  const LANDS = {
    forest: { name: '산림', color: '#3F9B5A' }, park: { name: '공원', color: '#8BD46E' }, farm: { name: '농경지', color: '#D9B44A' },
    res: { name: '주거지', color: '#F2A07B' }, com: { name: '상업지', color: '#E57BA8' }, ind: { name: '공업지', color: '#9A8FB8' },
    civic: { name: '공공시설', color: '#6FA8DC' }, water: { name: '수역', color: '#2E86C1' },
  };
  const landOf = (b) => b.land || (isGreen(b) ? 'park' : 'res'); // tiles built before classify_land.py
  const landNames = (ids) => ids.map((l) => LANDS[l].name).join('·');
  // mountains, the airport and river parks come out of the road network as one huge face; they open on the map but are not plots
  const MAX_M2 = 200000;
  const plot = (b) => b && b.isle && b.areaM2 <= MAX_M2;
  // Korean object particle, based on the final consonant of the name
  const jong = (w) => {
    const ch = String(w).slice(-1), d = '0123456789'.indexOf(ch);
    if (d >= 0) return [21, 8, 0, 16, 0, 0, 1, 8, 8, 0][d]; // 영 일 이 삼 사 오 육 칠 팔 구
    const c = ch.charCodeAt(0) - 0xAC00;
    return c >= 0 && c < 11172 ? c % 28 : 0;
  };
  const eul = (w) => (jong(w) ? '을' : '를');
  const MAX_SEL = 12;
  // the blocks one item is built on, seen as one plot; price and size add up block by block
  function groupOf(ids) {
    const bs = ids.map((id) => blocks.get(id));
    const area = bs.reduce((t, b) => t + b.area, 0), wet = bs.find((b) => b.water);
    return {
      ids, bs, area, n: bs.length,
      areaM2: bs.reduce((t, b) => t + b.areaM2, 0),
      green: bs.reduce((t, b) => t + b.green * b.area, 0) / (area || 1),
      water: wet ? wet.water : null, wetAt: bs.indexOf(wet),
      lands: [...new Set(bs.map(landOf))],
      land: Object.entries(bs.reduce((t, b) => ({ ...t, [landOf(b)]: (t[landOf(b)] || 0) + b.area }), {})).sort((x, y) => y[1] - x[1])[0][0],
      isle: bs.every((b) => b.isle),
      mult: bs.reduce((t, b) => t + tierOf(b).mult, 0),
    };
  }
  const sizeName = (g) => (g.n > 1 ? `구역 ${g.n}곳` : `${tierOf(g).name} 구역`);
  const priceOf = (kind, g) => Math.round(KINDS[kind].price * g.mult);
  const itemAt = (blockId) => S.items.find((i) => i.blocks.includes(blockId));
  const stageOf = (it) => STAGES.filter((km) => (it.km || 0) >= km).length;
  // how far into the current stage, 0..1 (1 at the last stage)
  const growthOf = (it) => { const st = stageOf(it); return st >= STAGES.length ? 1 : ((it.km || 0) - STAGES[st - 1]) / (STAGES[st] - STAGES[st - 1]); };
  // blocks first opened today get a warm tint on the map, so a drive's new ground stands out from the old
  const dayKey = () => { const d = new Date(); return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`; };
  if (!S.today || S.today.day !== dayKey()) S.today = { day: dayKey(), ids: [] };
  let todaySet = new Set(S.today.ids);
  function noteToday(ids) {
    if (S.today.day !== dayKey()) { S.today = { day: dayKey(), ids: [] }; todaySet = new Set(); shown.forEach((t) => { t.dirty = true; }); }
    ids.forEach((id) => { if (!todaySet.has(id)) { todaySet.add(id); S.today.ids.push(id); } });
  }
  const found = new Set(S.found || []);
  const unlocked = (kind) => !UNLOCK[kind] || found.has(UNLOCK[kind]);
  // terrain seen on newly read or revealed blocks; returns true when something new was found
  function noteFound(ids) {
    const before = found.size;
    ids.forEach((id) => { const b = blocks.get(id); if (!b) return; found.add(landOf(b)); if (b.water) found.add('shore'); });
    if (found.size === before) return false;
    S.found = [...found];
    return true;
  }

  function status(kind, b, fromInv) {
    const K = KINDS[kind];
    if (!b.isle) return { ok: false, badge: '구역이 너무 좁아요' };
    if (b.bs.some((x) => x.areaM2 > MAX_M2)) return { ok: false, badge: '구역이 너무 넓어요' };
    if (!fromInv && !unlocked(kind)) return { ok: false, locked: true, badge: `${FOUND_NAME[UNLOCK[kind]]} 발견 필요`, msg: `${FOUND_NAME[UNLOCK[kind]]} 구역을 발견하면 열려요` };
    const no = b.lands.filter((l) => !K.lands.includes(l));
    if (no.length) return { ok: false, badge: `${landNames(no)} 불가`, msg: `${landNames(no)}에는 놓을 수 없어요`, lands: true };
    if (K.water && !b.water) return { ok: false, badge: '강가 전용' };
    if (!fromInv && K.max && S.items.concat(S.inventory).filter((i) => i.kind === kind).length >= K.max) return { ok: false, badge: '집은 한 채만 지을 수 있어요' };
    if (!fromInv && priceOf(kind, b) > miles) return { ok: false, badge: '코인이 부족해요' };
    const fit = K.best.includes(b.land);
    return { ok: true, fit, badge: fit ? `${LANDS[b.land].name} 추천` : '배치 가능' };
  }

  // ---------- builders fitted to the block ----------
  const areaOf = (g) => areaOfMany(g.bs.map((b) => area({ outer: b.isle, holes: [], area: b.area })));
  let animals = [], swayers = [], docks = [];
  function flowerPatch(group, A, n, taken) {
    let made = 0;
    for (let i = 0; i < n; i++) {
      const p = randomInside(A, 0.5, taken, 0.3); if (!p) break;
      const c = ['#FF8FB8', '#FFD84A', '#FFFFFF', '#B99BFF', '#FF7A6B'][i % 5];
      group.add(mesh(cyl(0.025, 0.025, 0.3, 5), '#3FB66A', { p: [p.x, ISLAND_H + 0.15, p.z] }));
      group.add(mesh(sph(0.13, 8), c, { p: [p.x, ISLAND_H + 0.33, p.z], s: [1, 0.6, 1] }));
      group.add(mesh(sph(0.05, 6), '#FFE27A', { p: [p.x, ISLAND_H + 0.38, p.z] }));
      made++;
    }
    return made;
  }
  function plantTrees(group, A, n, taken, gap, kinds, owner) {
    let made = 0;
    for (let i = 0; i < n; i++) {
      const p = randomInside(A, 0.9, taken, gap) || randomInside(A, 0.4, taken, gap * 0.6); if (!p) break;
      const k = kinds(i);
      const t = k === 'pine' ? pine() : roundTree(k);
      t.position.set(p.x, ISLAND_H, p.z); t.scale.setScalar(0.8 + rnd() * 0.35); t.rotation.y = rnd() * 6;
      group.add(t); swayers.push({ obj: t, owner });
      taken.push({ x: p.x, z: p.z, r: 0.3 });
      made++;
    }
    return made;
  }
  const perimeterM = (g) => Math.round(g.bs.reduce((t, b) => t + b.isle.reduce((s, p, i) => { const q = b.isle[(i + 1) % b.isle.length]; return s + Math.hypot(q[0] - p[0], q[1] - p[1]); }, 0) * Math.sqrt(b.areaM2 / b.area), 0));
  function parasol(color) {
    const g = new THREE.Group();
    g.add(mesh(cyl(0.05, 0.05, 1.6, 6), '#8A5A35', { p: [0, 0.8, 0] }));
    g.add(mesh(new THREE.ConeGeometry(0.9, 0.45, 10), color, { p: [0, 1.75, 0] }));
    g.add(mesh(cyl(0.45, 0.45, 0.08, 14), '#FFF6E8', { p: [0, 0.7, 0] }));
    return g;
  }
  const BUILD = {
    trees(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#7AD86E'));
      const taken = [];
      const n = plantTrees(g, A, clamp(Math.round((A.size / 9) * k), 4, 48), taken, 1.4, (i) => (i % 4 === 0 ? 'cherry' : i % 3 === 0 ? 'green' : 'pine'), id);
      flowerPatch(g, A, clamp(Math.round(A.size / 25), 4, 16), taken);
      return { g, h: 4.2, what: [['나무', `${n}그루`]] };
    },
    flowers(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#9BE584'));
      const taken = [];
      const t = plantTrees(g, A, clamp(Math.round(A.size / 120), 1, 4), taken, 2, () => 'cherry', id);
      const n = flowerPatch(g, A, clamp(Math.round((A.size / 3) * k), 12, 160), taken);
      return { g, h: 3.4, what: [['꽃', `${n}송이`], ['벚나무', `${t}그루`]] };
    },
    pond(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#8EE07A'));
      const p = pole(A);
      const r = Math.max(0.3, Math.min(clamp(p.r * 0.6, 1, 5) * Math.min(1.2, k), (p.r - 0.35) / 1.25 - 0.2));
      g.add(mesh(cyl(r, r + 0.2, 0.1, 40), '#6FD0F5', { p: [p.x, ISLAND_H + 0.06, p.z], s: [1.25, 1, 0.9] }));
      for (let i = 0; i < 3; i++) g.add(mesh(cyl(0.3, 0.3, 0.04, 12), '#5BC27A', { p: [p.x + (rnd() - 0.5) * r, ISLAND_H + 0.13, p.z + (rnd() - 0.5) * r * 0.6] }));
      const taken = [{ x: p.x, z: p.z, r: r * 1.35 }];
      const n = plantTrees(g, A, clamp(Math.round(A.size / 25), 3, 14), taken, 1.5, (i) => (i % 2 ? 'green' : 'cherry'), id);
      flowerPatch(g, A, clamp(Math.round(A.size / 20), 6, 24), taken);
      return { g, h: 3.6, what: [['연못', `지름 ${Math.round(r * 2 * 10 * Math.sqrt(b.areaM2 / b.area))} m`], ['나무', `${n}그루`]] };
    },
    farm(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#8EE07A')); g.add(fenceAlong(A, 0.7, 1.5));
      const fp = pole(A);
      const sc = clamp(fp.r / 3.2, 0.5, 1.15);
      const bn = barn(); bn.scale.setScalar(sc); bn.position.set(fp.x, ISLAND_H - 0.5 * sc, fp.z); bn.rotation.y = longestEdgeAngle(partAt(A, fp.x, fp.z)); g.add(bn);
      const taken = [{ x: fp.x, z: fp.z, r: 3 * sc }];
      const t = plantTrees(g, A, clamp(Math.round(A.size / 70), 2, 6), taken, 1.6, (i) => (i % 3 ? 'green' : 'cherry'), id);
      let n = 0;
      for (let i = 0; i < clamp(Math.round((A.size / 45) * k), 2, 12); i++) {
        const p = randomInside(A, 1.2, taken, 0.8) || randomInside(A, 0.5, taken, 0.5); if (!p) break;
        const s = sheep(); s.position.set(p.x, ISLAND_H, p.z); s.rotation.y = rnd() * 6; g.add(s);
        animals.push({ obj: s, area: partAt(A, p.x, p.z), speed: 0.7, owner: id }); n++;
      }
      flowerPatch(g, A, clamp(Math.round(A.size / 25), 6, 18), taken);
      return { g, h: 3.4 + 3.2 * sc, what: [['양', `${n}마리`], ['울타리', `${fmt(perimeterM(b))} m`], ['헛간 · 나무', `1채 · ${t}그루`]] };
    },
    deer(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#7AD86E'));
      const sp = pole(A);
      const pondR = clamp(sp.r * 0.4, 0.8, 3);
      const pondAt = { x: sp.x - sp.r * 0.35, z: sp.z + sp.r * 0.2 };
      g.add(mesh(cyl(pondR, pondR + 0.2, 0.1, 40), '#6FD0F5', { p: [pondAt.x, ISLAND_H + 0.06, pondAt.z], s: [1.3, 1, 0.85] }));
      const taken = [{ x: pondAt.x, z: pondAt.z, r: pondR * 1.4 }, { x: sp.x + sp.r * 0.3, z: sp.z, r: 2.4 }];
      const t = plantTrees(g, A, clamp(Math.round(A.size / 12), 6, 32), taken, 1.5, (i) => (i % 4 === 0 ? 'cherry' : i % 3 === 0 ? 'green' : 'pine'), id);
      const adults = clamp(Math.round((A.size / 80) * k), 2, 5);
      for (let i = 0; i <= adults; i++) {
        const fawn = i === adults;
        const p = randomInside(A, 1, taken, 0.5) || { x: sp.x, z: sp.z };
        const d = deer(fawn); d.position.set(p.x, ISLAND_H, p.z); d.rotation.y = rnd() * 6; g.add(d);
        animals.push({ obj: d, area: partAt(A, p.x, p.z), speed: fawn ? 0.9 : 0.6, owner: id });
      }
      flowerPatch(g, A, clamp(Math.round(A.size / 20), 6, 20), taken);
      return { g, h: 4.4, what: [['사슴', `${adults}마리 · 아기 사슴 1마리`], ['나무', `${t}그루`]] };
    },
    home(A, b, k, id, look = ['#5BB8F5', '#FFF6E6']) {
      const g = new THREE.Group(); g.add(island(A, '#9BE584'));
      const hp = pole(A);
      const s = clamp((hp.r * 2 * 0.8) / 4.0, 0.3, 1.1);
      const hs = house(...look); hs.scale.setScalar(s); hs.position.set(hp.x, ISLAND_H - 0.5 * s, hp.z); hs.rotation.y = longestEdgeAngle(partAt(A, hp.x, hp.z)); g.add(hs);
      const taken = [{ x: hp.x, z: hp.z, r: 2.2 * s }];
      const t = plantTrees(g, A, clamp(Math.round(A.size / 60), 1, 5), taken, 1, (i) => (i % 2 ? 'green' : 'apple'), id);
      const mb = randomInside(A, 0.3, taken, 0.2);
      if (mb) { const m = mailbox(); m.scale.setScalar(s); m.position.set(mb.x, ISLAND_H, mb.z); g.add(m); taken.push({ x: mb.x, z: mb.z, r: 0.4 }); }
      flowerPatch(g, A, clamp(Math.round((A.size / 15) * k), 4, 40), taken);
      return { g, h: 1.2 + 4.6 * s, what: [['건물', '1채'], ['정원수', `${t}그루`]], taken, s };
    },
    cafe(A, b, k, id) {
      const r = BUILD.home(A, b, k, id, ['#4CC07A', '#FFFDF6']);
      for (let i = 0; i < 3; i++) {
        const p = randomInside(A, 0.8, r.taken, 0.6); if (!p) break;
        const u = parasol(['#FF8FB8', '#FFD84A', '#7CC8F0'][i]); u.scale.setScalar(r.s); u.position.set(p.x, ISLAND_H, p.z); r.g.add(u); r.taken.push({ x: p.x, z: p.z, r: 0.9 });
      }
      r.what = [['카페', '1채'], ['파라솔 테이블', '3개']];
      return r;
    },
    bakery(A, b, k, id) {
      const r = BUILD.home(A, b, k, id, ['#F5A05B', '#FFF1DE']);
      r.what = [['빵집', '1채'], ['정원', '꽃과 과일나무']];
      return r;
    },
    fishing(A, b, k, id) {
      const g = new THREE.Group(); g.add(island(A, '#9BE584'));
      const taken = [];
      flowerPatch(g, A, clamp(Math.round((A.size / 20) * k), 4, 32), taken);
      plantTrees(g, A, clamp(Math.round((A.size / 60) * k), 1, 5), taken, 1.5, () => 'green', id);
      const w = W(b.water[0], b.water[1]);
      const pp = pole(partsOf(A)[Math.max(0, b.wetAt)]); // the deck reaches out from the block on the water
      const dir = new THREE.Vector3(w.x - pp.x, 0, w.z - pp.z).normalize();
      const pier = dock();
      pier.position.set(w.x + dir.x * 1.7, 0, w.z + dir.z * 1.7);
      pier.rotation.y = -Math.atan2(dir.z, dir.x);
      g.add(pier); docks.push({ obj: pier, owner: id });
      return { g, h: 3.2, what: [['낚시 데크', '1개'], ['보트', '1척']] };
    },
  };

  // ---------- catalog thumbnails: each kind as a small diorama built from the same models as the map ----------
  const THUMB = {};
  function diorama(kind) {
    const g = new THREE.Group(), T = 0.5; // top of the pad
    const at = (o, x, z, sc = 1, ry = 0) => { o.position.set(x, T + o.position.y, z); o.scale.multiplyScalar(sc); o.rotation.y = ry; g.add(o); return o; };
    const water = (r, x = 0, z = 0) => g.add(mesh(cyl(r, r, 0.08, 40), '#6FD0F5', { p: [x, T + 0.03, z], s: [1.2, 1, 0.9] }));
    const bloom = (n, r) => { for (let i = 0; i < n; i++) { const a = i * 2.4, d = r * Math.sqrt((i + 0.5) / n), x = Math.cos(a) * d, z = Math.sin(a) * d; g.add(mesh(cyl(0.03, 0.03, 0.3, 5), '#3FB66A', { p: [x, T + 0.15, z] })); g.add(mesh(sph(0.17, 8), ['#FF8FB8', '#FFD84A', '#FFFFFF', '#B99BFF', '#FF7A6B'][i % 5], { p: [x, T + 0.34, z], s: [1, 0.6, 1] })); } };
    const homeLike = (roof, wall) => { const h = house(roof, wall); h.scale.setScalar(0.42); h.position.set(-0.1, T - 0.21, 0.25); h.rotation.y = -0.5; g.add(h); };
    if (kind === 'fishing') {
      g.add(mesh(cyl(2.3, 2.5, 0.5, 48), '#6FD0F5', { p: [0, 0.25, 0] }));
      g.add(mesh(cyl(2.5, 2.7, 0.25, 48), '#7A5A3E', { p: [0, 0.12, 0] }));
      g.add(mesh(cyl(1.0, 1.15, 0.35, 32), '#9BE584', { p: [-1.3, T + 0.05, -0.8] }));
      at(roundTree('green'), -1.6, -1.1, 0.5);
      const d = dock(); d.scale.setScalar(0.6); d.position.set(-0.3, T - 0.3, -0.2); d.rotation.y = 0.5; g.add(d);
    } else g.add(pad(2.2, kind === 'flowers' || kind === 'home' || kind === 'cafe' || kind === 'bakery' ? '#9BE584' : '#8EE07A'));
    if (kind === 'trees') { at(pine(), -0.8, -0.5, 0.95); at(roundTree('cherry'), 0.9, -0.1, 0.8); at(roundTree('green'), -0.1, 1.0, 0.7); bloom(5, 0.6); }
    if (kind === 'flowers') { at(roundTree('cherry'), -0.6, -1.0, 0.6); bloom(22, 1.7); }
    if (kind === 'pond') { water(1.1, 0.2, 0.3); [[0.5, 0.6], [-0.2, 0.1]].forEach(([x, z]) => g.add(mesh(cyl(0.25, 0.25, 0.04, 12), '#5BC27A', { p: [x, T + 0.09, z] }))); at(roundTree('green'), -1.1, -1.0, 0.65); bloom(4, 2.0); }
    if (kind === 'farm') { const b = barn(); b.scale.setScalar(0.34); b.position.set(0.5, T - 0.17, -0.9); b.rotation.y = -0.3; g.add(b); at(sheep(), -0.7, 0.5, 0.85, -0.4); at(sheep(), 0.4, 1.1, 0.7, -1.0); }
    if (kind === 'deer') { at(roundTree('green'), -1.1, -0.9, 0.55); at(deer(false), -0.2, 0.3, 0.78, -0.5); at(deer(true), 0.9, 1.0, 0.78, -0.9); }
    if (kind === 'home') { homeLike('#5BB8F5', '#FFF6E6'); const m = mailbox(); m.scale.setScalar(0.7); m.position.set(1.3, T, 1.2); g.add(m); bloom(5, 0.5); }
    if (kind === 'cafe') { homeLike('#4CC07A', '#FFFDF6'); const u = parasol('#FF8FB8'); u.scale.setScalar(0.6); u.position.set(1.4, T, 1.1); g.add(u); }
    if (kind === 'bakery') { homeLike('#F5A05B', '#FFF1DE'); at(roundTree('apple'), 1.4, 0.9, 0.5); }
    return g;
  }
  function makeThumbs() {
    let r;
    try { r = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true }); } catch (e) { return; } // the line icons stay
    r.setPixelRatio(1); r.setSize(292, 180); r.outputEncoding = THREE.sRGBEncoding;
    const sc = new THREE.Scene();
    sc.add(new THREE.HemisphereLight(0xe6f6ff, 0xffd9b0, 0.5));
    const l = new THREE.DirectionalLight(0xfff0d6, 0.7); l.position.set(-4, 8, 5); sc.add(l);
    const cam = new THREE.PerspectiveCamera(24, 292 / 180, 0.1, 100);
    cam.position.set(4.0, 4.5, 5.8); cam.lookAt(0, 0.95, 0.15);
    Object.keys(KINDS).forEach((k) => {
      const d = diorama(k); sc.add(d);
      r.render(sc, cam); THUMB[k] = r.domElement.toDataURL();
      sc.remove(d);
    });
    r.dispose(); r.forceContextLoss();
  }
  // a kind's picture on a sky tile; w x h in px, with optional content laid over it (the price chip on catalog cards)
  const art = (kind, w, h = w, over = '') => `<span class="thumb" style="width:${w}px;height:${h}px">${THUMB[kind] ? `<img src="${THUMB[kind]}" alt="">` : svg(ICON[KINDS[kind].icon], Math.round(h / 2), 2)}${over}</span>`;

  // ---------- scene <-> state ----------
  const built = new Map(); // item id -> { g, h, what, tag, sig, at }
  const sigOf = (it) => `${it.kind}|${it.blocks}|${it.seed}|${stageOf(it)}`;
  function make(it) {
    const b = groupOf(it.blocks), A = areaOf(b);
    seed = it.seed;
    const out = BUILD[it.kind](A, b, GROW_K[stageOf(it) - 1], it.id);
    out.g.traverse((o) => { o.userData.item = it.id; });
    const c = pole(A);
    out.at = new THREE.Vector3(c.x, 0, c.z);
    scene.add(out.g);
    return out;
  }
  function unmake(id) {
    const v = built.get(id); if (!v) return;
    scene.remove(v.g);
    animals = animals.filter((a) => a.owner !== id);
    swayers = swayers.filter((a) => a.owner !== id);
    docks = docks.filter((a) => a.owner !== id);
    built.delete(id);
  }
  function syncScene() {
    const want = new Map(S.items.map((it) => [it.id, it]));
    [...built.keys()].forEach((id) => { if (id !== 'preview' && (!want.has(id) || built.get(id).sig !== sigOf(want.get(id)))) unmake(id); });
    S.items.forEach((it) => {
      if (!built.has(it.id)) { const v = make(it); v.sig = sigOf(it); built.set(it.id, v); }
    });
    animals.forEach((a) => { if (!a.goal) { a.goal = a.obj.position.clone(); a.wait = Math.random() * 2; a.obj.userData.baseY = a.obj.userData.body.position.y; } });
    shown.forEach(updateTowers);
  }

  // ---------- towers and landmarks ----------
  // Seoul's tall buildings (20 floors or 66 m and up, from tools/add_towers.py) stand on revealed blocks as pastel toy
  // models built from their real footprints. Slab apartments (판상형) get balcony bands, painted end walls and rooftop
  // elevator houses; tower apartments (탑상형) a stepped crown; offices glass walls, a setback top and roof plant; hotels
  // a crown. Buildings with OSM building:part pieces keep their real tiers. Heights are squashed (square root) so a 555 m
  // tower does not dwarf the village. A block with an item hides its towers, because the item fills the whole block.
  // Each tile merges its shown towers into one mesh per material and rebuilds it only when that set changes.
  const towerH = (m) => 3 * Math.sqrt(Math.max(m, 0) / 30);
  const hashOf = (id, k) => { const v = Math.sin(id * 12.9898 + k * 78.233) * 43758.5453; return v - Math.floor(v); };
  const ACCENT = ['#F7B7A3', '#9ED9C3', '#A9CDEB', '#C9B6E8', '#F6D58E', '#F4A6B8'];
  const GLASS = ['#CFE4F4', '#BFE3D8', '#DCD3F2', '#F3D9C9', '#D4E6EE', '#E9E3CF'];
  const WALL = ['#FFF8EE', '#FFF8EE', '#FFF1E6', '#F3F7FF', '#F1F9F2'];
  function facadeTex(draw, ru, rv) {
    const c = document.createElement('canvas'); c.width = c.height = 64;
    draw(c.getContext('2d'));
    const t = new THREE.CanvasTexture(c); t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(ru, rv); t.encoding = THREE.sRGBEncoding;
    t.anisotropy = renderer.capabilities.getMaxAnisotropy();
    return t;
  }
  const FACADE = {
    // balconies: a window strip with a railing under it on every floor
    apt: facadeTex((g) => { g.fillStyle = '#FFFFFF'; g.fillRect(0, 0, 64, 64); g.fillStyle = '#B9CFE4'; g.fillRect(4, 12, 56, 26); g.fillStyle = '#E6DED2'; g.fillRect(0, 40, 64, 7); }, 1.6, 2.4),
    // curtain wall: tall glass panes between white mullions
    glass: facadeTex((g) => { g.fillStyle = '#FFFFFF'; g.fillRect(0, 0, 64, 64); g.fillStyle = '#AFCBE3'; [3, 35].forEach((x) => g.fillRect(x, 2, 26, 58)); g.fillStyle = 'rgba(255,255,255,0.55)'; [3, 35].forEach((x) => g.fillRect(x + 4, 2, 5, 58)); }, 2.2, 1.6),
    // ribbon windows: a long glass strip on every floor between plain spandrels
    band: facadeTex((g) => { g.fillStyle = '#FFFFFF'; g.fillRect(0, 0, 64, 64); g.fillStyle = '#A9C6DF'; g.fillRect(0, 14, 64, 30); g.fillStyle = 'rgba(255,255,255,0.5)'; g.fillRect(0, 16, 64, 5); }, 1, 2.2),
    // teal glass behind close-set white vertical fins (현대자동차 강남대로 사옥)
    fins: facadeTex((g) => {
      const gr = g.createLinearGradient(0, 0, 64, 0); gr.addColorStop(0, '#5FA8A3'); gr.addColorStop(0.5, '#7CC0B8'); gr.addColorStop(1, '#5A9F9C');
      g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
      g.fillStyle = 'rgba(30,70,72,0.35)'; g.fillRect(0, 30, 64, 3); g.fillRect(0, 62, 64, 2); // floor lines
      g.fillStyle = '#F4F8F7'; for (let x = 0; x < 64; x += 8) g.fillRect(x, 0, 3, 64);
    }, 3.2, 2.6),
    // punched windows in a solid wall
    grid: facadeTex((g) => { g.fillStyle = '#FFFFFF'; g.fillRect(0, 0, 64, 64); g.fillStyle = '#B3CBE0'; [[6, 10], [36, 10], [6, 40], [36, 40]].forEach(([x, y]) => g.fillRect(x, y, 22, 16)); }, 2, 2.4),
  };
  const OFFICE_TEX = ['glass', 'glass', 'band', 'grid'];
  const facadeMats = {};
  const facadeMat = (tex, hex) => facadeMats[tex + hex] || (facadeMats[tex + hex] = new THREE.MeshToonMaterial({ color: hex, gradientMap, map: FACADE[tex] }));
  const W2 = ([x, y]) => [x / 10 - 64, y / 10 - 36];
  // white emblem and wordmark, drawn once
  const HYUNDAI_SIGN = (() => {
    const c = document.createElement('canvas'); c.width = 512; c.height = 72;
    const g = c.getContext('2d'); g.fillStyle = '#FFFFFF'; g.strokeStyle = '#FFFFFF';
    g.lineWidth = 7; g.beginPath(); g.ellipse(70, 36, 50, 27, 0, 0, Math.PI * 2); g.stroke();
    g.save(); g.translate(70, 36); g.transform(1, 0, -0.35, 1, 0, 0); g.fillRect(-24, -17, 10, 34); g.fillRect(14, -17, 10, 34); g.fillRect(-16, -5, 32, 9); g.restore();
    g.font = "bold 52px 'Arial Black', Arial, sans-serif"; g.textBaseline = 'middle'; g.fillText('HYUNDAI', 140, 38);
    const t = new THREE.CanvasTexture(c); t.encoding = THREE.sRGBEncoding; t.anisotropy = renderer.capabilities.getMaxAnisotropy();
    return new THREE.MeshBasicMaterial({ map: t, transparent: true, depthWrite: false, side: THREE.DoubleSide });
  })();
  // a prism over the footprint `pts` (world x,z) from y0 to y1, scaled by s about the footprint centre
  function prism(pts, y0, y1, s = 1) {
    const cx = pts.reduce((a, p) => a + p[0], 0) / pts.length, cz = pts.reduce((a, p) => a + p[1], 0) / pts.length;
    const g = new THREE.ExtrudeGeometry(new THREE.Shape(pts.map(([x, z]) => new THREE.Vector2((x - cx) * s, (cz - z) * s))), { depth: y1 - y0, bevelEnabled: false });
    g.rotateX(-Math.PI / 2); g.translate(cx, y0, cz);
    return g;
  }
  const boxAt = (w, h, d, x, y, z, ry = 0) => { const g = new THREE.BoxGeometry(w, h, d).toNonIndexed(); g.rotateY(ry); g.translate(x, y + h / 2, z); return g; };
  // centre, long axis angle and the length / width along it (a rough oriented box from the point spread)
  function axisOf(pts) {
    const n = pts.length, cx = pts.reduce((a, p) => a + p[0], 0) / n, cz = pts.reduce((a, p) => a + p[1], 0) / n;
    let sxx = 0, szz = 0, sxz = 0;
    pts.forEach(([x, z]) => { sxx += (x - cx) ** 2; szz += (z - cz) ** 2; sxz += (x - cx) * (z - cz); });
    const a = 0.5 * Math.atan2(2 * sxz, sxx - szz), ux = Math.cos(a), uz = Math.sin(a);
    const along = pts.map(([x, z]) => (x - cx) * ux + (z - cz) * uz), across = pts.map(([x, z]) => -(x - cx) * uz + (z - cz) * ux);
    const lo = Math.min(...along), hi = Math.max(...along), lo2 = Math.min(...across), hi2 = Math.max(...across);
    const mx = cx + ux * (lo + hi) / 2 - uz * (lo2 + hi2) / 2, mz = cz + uz * (lo + hi) / 2 + ux * (lo2 + hi2) / 2;
    return { x: mx, z: mz, a, ux, uz, L: hi - lo, W: hi2 - lo2 };
  }
  // a rectangle around (x, z), `l` along the angle a and `w` across it
  const rectPts = (x, z, a, l, w) => [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([i, j]) => [x + Math.cos(a) * i * l / 2 - Math.sin(a) * j * w / 2, z + Math.sin(a) * i * l / 2 + Math.cos(a) * j * w / 2]);
  // hand-made shapes for the landmarks people will look for; the rest follow their type
  const LANDMARKS = {
    // two towers on one podium
    '현대자동차그룹 본사': (add, pts, h, ax) => {
      const mat = facadeMat('band', '#CFE0EE');
      add('#FFFFFF', prism(pts, 0, 0.5, 1.02));
      [-1, 1].forEach((e) => {
        const r = rectPts(ax.x + ax.ux * ax.L * 0.27 * e, ax.z + ax.uz * ax.L * 0.27 * e, ax.a, ax.L * 0.4, ax.W * 0.92);
        add(mat, prism(r, 0.5, h));
        add('#8FB4D2', prism(r, h, h + 0.14, 1.04));
        add('#E8EEF2', boxAt(ax.L * 0.12, 0.3, ax.W * 0.4, ax.x + ax.ux * ax.L * 0.27 * e, h + 0.14, ax.z + ax.uz * ax.L * 0.27 * e, -ax.a));
      });
    },
    // 스케일타워 (2023): glass lobby, a dark recessed garden floor with planters, a teal glass body behind white fins,
    // and the HYUNDAI sign high on the face toward 강남대로
    '현대자동차 강남대로 사옥': (add, pts, h) => {
      const lobby = h * 0.11, garden = h * 0.055;
      add(facadeMat('glass', '#E6F2F4'), prism(pts, 0, lobby, 0.96));
      add('#46524F', prism(pts, lobby, lobby + garden, 0.92));
      add('#86CF8E', prism(pts, lobby, lobby + garden * 0.4, 0.99));
      add(facadeMat('fins', '#FFFFFF'), prism(pts, lobby + garden, h));
      add('#4F8C87', prism(pts, h, h + 0.08, 1.0));
      // the sign on the face toward 강남대로 (farthest west) and on the south face
      const n = pts.length, cx = pts.reduce((a, p) => a + p[0], 0) / n, cz = pts.reduce((a, p) => a + p[1], 0) / n;
      const faces = pts.map((p, i) => { const q = pts[(i + 1) % n]; return { mx: (p[0] + q[0]) / 2, mz: (p[1] + q[1]) / 2, len: Math.hypot(q[0] - p[0], q[1] - p[1]) }; });
      const west = faces.reduce((a, f) => (f.mx < a.mx ? f : a)), south = faces.reduce((a, f) => (f.mz > a.mz ? f : a));
      [west, south].forEach((f) => {
        const nx = f.mx - cx, nz = f.mz - cz, nl = Math.hypot(nx, nz) || 1;
        const sign = new THREE.PlaneGeometry(f.len * 0.5, f.len * 0.07);
        sign.rotateY(Math.atan2(nx / nl, nz / nl));
        sign.translate(f.mx + (nx / nl) * 0.03, h - f.len * 0.08, f.mz + (nz / nl) * 0.03);
        add(HYUNDAI_SIGN, sign);
      });
    },
    // a tapering tower in eight tiers with a lantern and spire on top
    '롯데월드타워': (add, pts, h) => {
      const mat = facadeMat('glass', '#DCE7EF');
      for (let i = 0; i < 8; i++) add(mat, prism(pts, (h * 0.86 * i) / 8, (h * 0.86 * (i + 1)) / 8, 1 - i * 0.07));
      add('#F4F6F8', prism(pts, h * 0.86, h, 0.42));
      add('#F2F2F2', boxAt(0.1, 1.2, 0.1, ...[pts.reduce((a, p) => a + p[0], 0) / pts.length, h, pts.reduce((a, p) => a + p[1], 0) / pts.length]));
    },
    // gold glass with a stepped top
    '63빌딩': (add, pts, h) => {
      const mat = facadeMat('glass', '#F3D27A');
      add(mat, prism(pts, 0, h * 0.9));
      add(mat, prism(pts, h * 0.9, h, 0.8));
      add('#E7B94F', prism(pts, h, h + 0.12, 0.84));
    },
  };
  // the pieces of one tower as [material, geometry in world space]
  function towerPieces([id, m, , kind, name, ring, parts]) {
    const out = [], add = (mat, geo) => out.push([mat, geo]);
    const pts = ring.map(W2), h = towerH(m), ax = axisOf(pts);
    const accent = ACCENT[Math.floor(hashOf(id, 1) * ACCENT.length)];
    if (LANDMARKS[name]) { LANDMARKS[name](add, pts, h, ax); return out; }
    if (parts && parts.length) {
      const mat = kind === 'p' || kind === 't' ? facadeMat('apt', '#FFF8EE') : facadeMat('glass', GLASS[Math.floor(hashOf(id, 2) * GLASS.length)]);
      parts.forEach(([lo, hi, r]) => add(mat, prism(r.map(W2), towerH(lo), towerH(hi))));
      if (m >= 300) add('#F2F2F2', boxAt(0.12, 2.2, 0.12, ax.x, h, ax.z));
      return out;
    }
    if (kind === 'p' || kind === 't') {
      add(facadeMat('apt', WALL[Math.floor(hashOf(id, 4) * WALL.length)]), prism(pts, 0.3, h));
      add('#E9DCCB', prism(pts, 0, 0.3, 0.97)); // pilotis at the ground floor
      add(accent, prism(pts, h, h + 0.14, 1.03)); // parapet
      if (kind === 'p') {
        // painted end walls (the 동 number side) and two elevator houses on the roof
        [-1, 1].forEach((e) => {
          const x = ax.x + ax.ux * (ax.L / 2 + 0.02) * e, z = ax.z + ax.uz * (ax.L / 2 + 0.02) * e;
          add(accent, boxAt(0.05, h * 0.72, ax.W * 0.5, x, h * 0.16, z, -ax.a));
          add('#F6EBDD', boxAt(Math.min(0.6, ax.L * 0.12), 0.45, ax.W * 0.6, ax.x + ax.ux * ax.L * 0.25 * e, h, ax.z + ax.uz * ax.L * 0.25 * e, -ax.a));
        });
      } else {
        add(accent, prism(pts, h + 0.14, h + 0.6, 0.8)); // stepped crown
        add('#FFFFFF', prism(pts, h + 0.6, h + 0.8, 0.55));
      }
      return out;
    }
    const glass = facadeMat(kind === 'h' ? 'grid' : OFFICE_TEX[Math.floor(hashOf(id, 3) * OFFICE_TEX.length)], kind === 'h' ? '#E6DCF5' : GLASS[Math.floor(hashOf(id, 2) * GLASS.length)]);
    const top = m >= 120 ? h * 0.84 : h;
    add('#FFFFFF', prism(pts, 0, 0.45, 1.02)); // lobby
    add(glass, prism(pts, 0.45, top));
    if (top < h) add(glass, prism(pts, top, h, 0.84)); // setback top
    add(kind === 'h' ? '#B79BDB' : '#9FC3DE', prism(pts, h, h + 0.12, top < h ? 0.88 : 1.03));
    if (kind === 'h') add('#B79BDB', prism(pts, h + 0.12, h + 0.55, 0.6));
    else add('#E8EEF2', boxAt(Math.min(ax.L, 1.2) * 0.45, 0.35, Math.min(ax.W, 1.2) * 0.45, ax.x, h + 0.12, ax.z, -ax.a)); // roof plant
    if (m >= 200) add('#F2F2F2', boxAt(0.1, 1.4, 0.1, ax.x, h + 0.4, ax.z));
    return out;
  }
  // merges geometries (all non-indexed) into one, remembering which block each triangle belongs to
  function mergeGeos(list) {
    const n = list.reduce((a, [g]) => a + g.attributes.position.count, 0);
    const pos = new Float32Array(n * 3), nor = new Float32Array(n * 3), uv = new Float32Array(n * 2), owners = [];
    let o = 0;
    list.forEach(([g, block]) => {
      const c = g.attributes.position.count;
      pos.set(g.attributes.position.array, o * 3); nor.set(g.attributes.normal.array, o * 3);
      if (g.attributes.uv) uv.set(g.attributes.uv.array, o * 2);
      owners.push([o / 3, block]);
      o += c;
    });
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
    geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    geo.computeBoundingSphere();
    geo.userData.owners = owners;
    return geo;
  }
  // rebuilds a tile's tower meshes when the set of towers it should show has changed
  function updateTowers(t) {
    const list = t.data.towers || [], want = [];
    list.forEach((tw, i) => { if (revealed.has(tw[0]) && !itemAt(tw[0])) want.push(i); });
    const sig = want.join(',');
    if (sig === t.towerSig) return;
    t.towerSig = sig;
    t.towerMeshes.forEach((mm) => { scene.remove(mm); mm.geometry.dispose(); });
    t.towerMeshes = [];
    const byMat = new Map();
    want.forEach((i) => {
      if (!t.towerPieces.has(i)) t.towerPieces.set(i, towerPieces(list[i]).map(([mat, g]) => [mat, g.index ? g.toNonIndexed() : g]));
      t.towerPieces.get(i).forEach(([mat, g]) => { if (!byMat.has(mat)) byMat.set(mat, []); byMat.get(mat).push([g, list[i][0]]); });
    });
    byMat.forEach((geos, mat) => {
      const mm = mesh(mergeGeos(geos), mat);
      scene.add(mm); t.towerMeshes.push(mm);
    });
  }
  const towerBlockAt = (hit) => {
    const ow = hit.object.geometry.userData.owners;
    if (!ow) return null;
    let lo = 0, hi = ow.length - 1;
    while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (ow[mid][0] <= hit.faceIndex) lo = mid; else hi = mid - 1; }
    return ow[lo][1];
  };

  // ---------- revealed ground tiles ----------
  // Revealed ground, one canvas per tile near the camera. Each tile draws its ground vectors (land, parks, water, roads,
  // dong names) and keeps them only inside the revealed blocks, through a mask blurred once for soft edges.
  // The mask is drawn with a margin so the blur does not fade at tile seams.
  const PAINT_R = 1500, DROP_R = 2300, TEX = 1024, M = 12;
  const shown = new Map(); // key -> { mesh, canvas, tex, dirty }
  const maskCv = document.createElement('canvas'); maskCv.width = maskCv.height = TEX + 2 * M;
  const landCv = document.createElement('canvas'); landCv.width = landCv.height = TEX;
  function blocksOver(x0, y0, x1, y1) {
    const out = [];
    revealed.forEach((id) => { const b = blocks.get(id); if (b && b.bbox[2] >= x0 && b.bbox[0] <= x1 && b.bbox[3] >= y0 && b.bbox[1] <= y1) out.push(b); });
    return out;
  }
  function paintTile(key, t) {
    const [tx, ty] = key.split('_').map(Number), d = t.data, k = TEX / TILE, ox = tx * TILE, oy = ty * TILE;
    const bs = blocksOver(ox - M, oy - M, ox + TILE + M, oy + TILE + M);
    t.mesh.visible = bs.length > 0;
    updateTowers(t);
    if (!bs.length) return;
    const m = maskCv.getContext('2d');
    m.setTransform(1, 0, 0, 1, 0, 0); m.clearRect(0, 0, maskCv.width, maskCv.height);
    m.setTransform(k, 0, 0, k, M - ox * k, M - oy * k);
    m.fillStyle = m.strokeStyle = '#000'; m.lineWidth = 5.2; m.lineJoin = 'miter'; // the block grown over its edge roads
    bs.forEach((b) => { m.beginPath(); b.edge.forEach(([x, y], i) => (i ? m.lineTo(x, y) : m.moveTo(x, y))); m.closePath(); m.fill(); m.stroke(); });
    const g = landCv.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = '#FCEECC'; g.fillRect(0, 0, TEX, TEX);
    g.setTransform(k, 0, 0, k, -ox * k, -oy * k);
    const fillAll = (rings, col) => { g.fillStyle = col; rings.forEach((r) => { g.beginPath(); r.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y))); g.closePath(); g.fill(); }); };
    fillAll(d.ground.green, '#96DC78');
    fillAll(d.ground.water, '#80C8EC');
    g.strokeStyle = '#FFFCF4'; g.lineCap = g.lineJoin = 'round';
    d.ground.roads.forEach(([w, pts]) => { g.lineWidth = w; g.beginPath(); pts.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y))); g.stroke(); });
    g.font = "15px 'Jua', sans-serif"; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = '#9C8A76';
    d.ground.labels.forEach(([n, x, y]) => g.fillText(n, x, y));
    const c = t.canvas.getContext('2d');
    c.globalCompositeOperation = 'source-over';
    c.clearRect(0, 0, TEX, TEX);
    c.drawImage(landCv, 0, 0);
    c.globalCompositeOperation = 'destination-in';
    c.filter = 'blur(3px)';
    c.drawImage(maskCv, -M, -M);
    c.filter = 'none';
    const fresh = bs.filter((b) => todaySet.has(b.id));
    if (fresh.length) {
      c.globalCompositeOperation = 'source-atop'; // only over ground already painted
      c.setTransform(k, 0, 0, k, -ox * k, -oy * k);
      c.fillStyle = 'rgba(255, 198, 70, 0.34)';
      fresh.forEach((b) => { c.beginPath(); b.edge.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath(); c.fill(); });
      c.setTransform(1, 0, 0, 1, 0, 0);
    }
    c.globalCompositeOperation = 'source-over';
    t.tex.needsUpdate = true;
  }
  function showTile(key, data) {
    const [tx, ty] = key.split('_').map(Number);
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = TEX;
    const tex = new THREE.CanvasTexture(canvas);
    tex.encoding = THREE.sRGBEncoding;
    tex.anisotropy = renderer.capabilities.getMaxAnisotropy();
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(TW, TW), new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false }));
    mesh.rotation.x = -Math.PI / 2;
    mesh.position.set(tx * TW + TW / 2 - 64, 0.01, ty * TW + TW / 2 - 36);
    mesh.renderOrder = -1;
    scene.add(mesh);
    const t = { mesh, canvas, tex, data, dirty: true, towerSig: '', towerMeshes: [], towerPieces: new Map() };
    shown.set(key, t);
    return t;
  }
  function dropTile(key) {
    const t = shown.get(key);
    scene.remove(t.mesh); t.mesh.geometry.dispose(); t.mesh.material.dispose(); t.tex.dispose();
    t.towerMeshes.forEach((mm) => { scene.remove(mm); mm.geometry.dispose(); });
    t.towerPieces.forEach((ps) => ps.forEach(([, g]) => g.dispose()));
    shown.delete(key);
  }
  // marks the shown tiles that the given blocks reach as needing a repaint
  function touchTiles(ids) {
    ids.forEach((id) => {
      const b = blocks.get(id); if (!b) return;
      shown.forEach((t, key) => {
        const [tx, ty] = key.split('_').map(Number);
        if (b.bbox[2] >= tx * TILE - M && b.bbox[0] <= (tx + 1) * TILE + M && b.bbox[3] >= ty * TILE - M && b.bbox[1] <= (ty + 1) * TILE + M) t.dirty = true;
      });
    });
  }
  let tilesBusy = false, lastTileAt = null;
  // keeps the tiles around the camera target painted; their neighbours are read too, because blocks owned by a
  // neighbour can reach into a tile
  async function updateTiles(force) {
    const cx = (controls.target.x + 64) * 10, cy = (controls.target.z + 36) * 10;
    if (tilesBusy || (!force && lastTileAt && Math.hypot(cx - lastTileAt[0], cy - lastTileAt[1]) < 200)) return;
    tilesBusy = true; lastTileAt = [cx, cy];
    try {
      const near = (r) => { const out = []; for (let ty = Math.floor((cy - r) / TILE); ty <= Math.floor((cy + r) / TILE); ty++) for (let tx = Math.floor((cx - r) / TILE); tx <= Math.floor((cx + r) / TILE); tx++) if (tileKey(tx, ty) in mapIndex.tiles) out.push(tileKey(tx, ty)); return out; };
      const want = near(PAINT_R);
      const read = near(PAINT_R + TILE);
      const before = blocks.size;
      await Promise.all(read.map(loadTile));
      [...shown.keys()].forEach((key) => { if (!near(DROP_R).includes(key)) dropTile(key); });
      for (const key of want) { if (!shown.has(key)) { const d = await loadTile(key); if (d) showTile(key, d); } }
      nameHere(cx, cy, read);
      if (blocks.size !== before) {
        shown.forEach((t) => { t.dirty = true; });
        if (mode === 'edit' && ui.view === 'home') render(); // empty-block counts include the blocks just read
      }
    } finally { tilesBusy = false; }
  }
  // the top bar names the neighbourhood the camera is looking at
  async function nameHere(cx, cy, keys) {
    let best = null, bd = Infinity;
    for (const key of keys) {
      const d = await loadTile(key); if (!d) continue;
      d.ground.labels.forEach(([n, x, y]) => { const dd = Math.hypot(x - cx, y - cy); if (dd < bd && /동|가$/.test(n)) { bd = dd; best = n; } });
    }
    $('placeName').textContent = revealed.size ? best || '' : '';
  }
  function paintDirty() {
    // at most two tiles per frame, nearest first, so a drive into new tiles never stalls a frame for long
    const cx = controls.target.x, cz = controls.target.z;
    [...shown.entries()].filter(([, t]) => t.dirty).sort((a, b) => a[1].mesh.position.distanceToSquared(controls.target) - b[1].mesh.position.distanceToSquared(controls.target))
      .slice(0, 2).forEach(([key, t]) => { t.dirty = false; paintTile(key, t); });
    void cx; void cz;
  }

  // ---------- selection highlight ----------
  // selected blocks: a soft fill on each block, and one dashed line around the whole range
  let selMesh = null;
  // the outline of the blocks grown by `pad` village px, so neighbours across a narrow road merge into one shape
  function outlineOf(bs, pad) {
    const cell = 2, pts = bs.flatMap((b) => b.isle || b.edge);
    const x0 = Math.min(...pts.map((p) => p[0])) - pad - 4, y0 = Math.min(...pts.map((p) => p[1])) - pad - 4;
    const w = Math.ceil((Math.max(...pts.map((p) => p[0])) + pad + 4 - x0) / cell), h = Math.ceil((Math.max(...pts.map((p) => p[1])) + pad + 4 - y0) / cell);
    const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
    const c = cv.getContext('2d');
    c.scale(1 / cell, 1 / cell); c.translate(-x0, -y0);
    c.fillStyle = c.strokeStyle = '#000'; c.lineWidth = pad * 2; c.lineJoin = 'round';
    bs.forEach((b) => { c.beginPath(); (b.isle || b.edge).forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath(); c.fill(); c.stroke(); });
    const px = c.getImageData(0, 0, w, h).data, on = (i, j) => i >= 0 && j >= 0 && i < w && j < h && px[(j * w + i) * 4 + 3] > 127;
    // walk the cell edges between filled and empty cells, clockwise, then chain them into loops
    const next = new Map(), key = (x, y) => x + ',' + y, add = (a, b) => next.set(key(...a), b);
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      if (!on(i, j)) continue;
      if (!on(i, j - 1)) add([i, j], [i + 1, j]);
      if (!on(i + 1, j)) add([i + 1, j], [i + 1, j + 1]);
      if (!on(i, j + 1)) add([i + 1, j + 1], [i, j + 1]);
      if (!on(i - 1, j)) add([i, j + 1], [i, j]);
    }
    const loops = [];
    while (next.size) {
      const [k0] = next.keys(); let at = k0.split(',').map(Number); const loop = [];
      while (next.has(key(...at))) { const b = next.get(key(...at)); next.delete(key(...at)); loop.push(at); at = b; }
      if (loop.length < 8) continue;
      let pl = loop.filter((p, i) => { const a = loop[(i + loop.length - 1) % loop.length], b = loop[(i + 1) % loop.length]; return (p[0] - a[0]) * (b[1] - p[1]) !== (p[1] - a[1]) * (b[0] - p[0]); });
      for (let r = 0; r < 3; r++) pl = pl.flatMap((p, i) => { const q = pl[(i + 1) % pl.length]; return [[0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]], [0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]]]; }); // round off the pixel stairs
      loops.push(pl.map(([x, y]) => W(x * cell + x0, y * cell + y0)));
    }
    return loops;
  }
  function highlight(ids) {
    if (selMesh) { scene.remove(selMesh); selMesh = null; }
    if (!ids || !ids.length) return;
    const bs = ids.map((id) => blocks.get(id));
    const g = new THREE.Group();
    const fillMat = new THREE.MeshBasicMaterial({ color: '#3FAE6A', transparent: true, opacity: 0.22, depthWrite: false });
    bs.forEach((b) => {
      const shape = new THREE.Shape((b.isle || b.edge).map(([x, y]) => new THREE.Vector2(x / 10 - 64, -(y / 10 - 36))));
      const fill = new THREE.Mesh(new THREE.ShapeGeometry(shape), fillMat);
      fill.rotation.x = -Math.PI / 2; fill.position.y = 0.05 + (itemAt(b.id) ? ISLAND_H + 0.05 : 0); g.add(fill);
    });
    const y = ids.some(itemAt) ? ISLAND_H + 0.25 : 0.12, dashMat = new THREE.MeshBasicMaterial({ color: '#2E8A51' });
    const DASH = 0.9, GAP = 0.55;
    outlineOf(bs, 7).forEach((loop) => {
      let carry = 0; // distance into the current dash/gap cycle, kept across corners so the pattern runs on
      loop.forEach((a, i) => {
        const b = loop[(i + 1) % loop.length], len = a.distanceTo(b), ang = -Math.atan2(b.z - a.z, b.x - a.x);
        for (let d = 0; d < len;) {
          const inDash = carry < DASH, left = inDash ? DASH - carry : DASH + GAP - carry, step = Math.min(left, len - d);
          if (inDash && step > 0.02) {
            const m = (d + step / 2) / len, e = new THREE.Mesh(box(step + 0.02, 0.1, 0.26), dashMat);
            e.position.set(a.x + (b.x - a.x) * m, y, a.z + (b.z - a.z) * m); e.rotation.y = ang; g.add(e);
          }
          d += step; carry = (carry + step) % (DASH + GAP);
        }
      });
    });
    scene.add(g); selMesh = g;
  }

  // ---------- persistence ----------
  function save() {
    if (bridge) bridge.saveState(JSON.stringify(S));
  }
  function commit(mutate) {
    mutate();
    save(); syncScene();
  }
  let noteTimer = 0;
  function note(msg) { const n = $('note'); n.textContent = msg; n.style.opacity = 1; clearTimeout(noteTimer); noteTimer = setTimeout(() => { n.style.opacity = 0; }, 2600); }

  // ---------- growth from finished drives: everything placed or stored grows by the distance driven ----------
  function applyTrips(trips) {
    const km = trips.reduce((t, x) => t + (x.km || 0), 0);
    const grown = [];
    S.items.concat(S.inventory).forEach((it) => {
      const before = stageOf(it);
      it.km = (it.km || 0) + km;
      if (stageOf(it) > before && S.items.includes(it)) grown.push(it.id);
    });
    return grown;
  }
  // kinds opened since the last trip summary
  function newUnlocks() {
    const open = Object.keys(KINDS).filter(unlocked);
    if (!S.seen) S.seen = open; // the first run on these rules announces nothing
    const fresh = open.filter((k) => !S.seen.includes(k));
    S.seen = open;
    return fresh;
  }

  // ---------- camera ----------
  let tween = null, follow = false;
  const NORTH_UP = new THREE.Vector3(4, 72, 70).normalize(); // the starting view: north at the top of the screen
  function goTo(target, dist, dir = camera.position.clone().sub(controls.target).normalize()) {
    follow = false;
    const goalPos = target.clone().add(dir.clone().multiplyScalar(dist));
    goalPos.y = Math.max(goalPos.y, dist * 0.62);
    tween = { t: 0, fromPos: camera.position.clone(), fromTarget: controls.target.clone(), toPos: goalPos, toTarget: target.clone() };
    if (reduce) tween.t = 1;
  }
  // where the map opens: where the car stopped, else the middle of the village, else the old 응봉·성수 view
  // turned back north-up, since a drive leaves the camera facing the way the car went
  function overview() {
    if (carKnown) return goTo(carGoal.clone(), 70, NORTH_UP);
    const pts = S.items.flatMap((it) => it.blocks.map((id) => blocks.get(id)).filter(Boolean).map((b) => [(b.bbox[0] + b.bbox[2]) / 2, (b.bbox[1] + b.bbox[3]) / 2]));
    if (!pts.length) return goTo(new THREE.Vector3(0, 0, 0), 98, NORTH_UP);
    const near = (p) => pts.filter((q) => Math.hypot(q[0] - p[0], q[1] - p[1]) < 400); // about 800 m
    const best = pts.map(near).reduce((a, b) => (b.length > a.length ? b : a));
    goTo(W(best.reduce((t, p) => t + p[0], 0) / best.length, best.reduce((t, p) => t + p[1], 0) / best.length), 70, NORTH_UP);
  }
  // the whole explored map in one view (after a drive): every revealed block read so far, or today's if that is too wide
  function overviewAll() {
    const fit = (ids) => {
      const bs = ids.map((id) => blocks.get(id)).filter(Boolean); if (!bs.length) return null;
      const x0 = Math.min(...bs.map((b) => b.bbox[0])), y0 = Math.min(...bs.map((b) => b.bbox[1]));
      const x1 = Math.max(...bs.map((b) => b.bbox[2])), y1 = Math.max(...bs.map((b) => b.bbox[3]));
      return { c: W((x0 + x1) / 2, (y0 + y1) / 2), span: Math.max((x1 - x0) / 10, ((y1 - y0) / 10) * 1.6) };
    };
    let f = fit([...revealed]);
    if (f && f.span > 230) f = fit([...todaySet]) || f;
    if (!f) return overview();
    const room = clamp((innerHeight * 0.75) / (viewOff.fh || innerHeight), 1, 1.6);
    goTo(f.c, clamp(f.span * 0.95 * room, 60, controls.maxDistance), NORTH_UP);
  }
  function focusBlocks(ids, k = 1.3) {
    const A = areaOf(groupOf(ids));
    const room = clamp((innerHeight * 0.7) / (viewOff.fh || innerHeight), 1, 2); // step back when panels leave a short strip of map
    goTo(new THREE.Vector3((A.min[0] + A.max[0]) / 2, 0, (A.min[1] + A.max[1]) / 2), clamp(Math.max(A.max[0] - A.min[0], A.max[1] - A.min[1]) * k, 16, 75) * room);
  }

  // the car, shown while driving
  const car = new THREE.Group();
  car.add(mesh(box(1.7, 0.55, 1.0), '#FF7A59', { p: [0, 0.55, 0] }));
  car.add(mesh(box(0.95, 0.45, 0.86), '#FFF8EC', { p: [-0.12, 1.02, 0] }));
  [[0.55, 0.5], [0.55, -0.5], [-0.55, 0.5], [-0.55, -0.5]].forEach(([x, z]) => car.add(mesh(cyl(0.26, 0.26, 0.2, 14), '#5B4636', { p: [x, 0.28, z], r: [Math.PI / 2, 0, 0] })));
  car.add(mesh(sph(0.1, 8), '#FFE27A', { p: [0.86, 0.6, 0.3] }));
  car.add(mesh(sph(0.1, 8), '#FFE27A', { p: [0.86, 0.6, -0.3] }));
  car.scale.setScalar(1.3);
  car.visible = false;
  scene.add(car);
  const carGoal = new THREE.Vector3();
  let carKnown = false;
  // heading as an angle in the ground plane (0 = +x); the car turns toward it and the camera swings behind it more slowly
  // the first heading of a drive is taken as is, so the view does not swing round from wherever the last one ended
  let goalHeading = -Math.PI / 2, carHeading = -Math.PI / 2, camHeading = null, headingFresh = true;
  function moveCar(x, y) {
    const p = W(x, y);
    if (carKnown && p.distanceTo(carGoal) > 0.05) {
      goalHeading = Math.atan2(p.z - carGoal.z, p.x - carGoal.x);
      if (headingFresh) { carHeading = camHeading = goalHeading; headingFresh = false; }
    }
    carGoal.copy(p);
    if (!carKnown) car.position.copy(p);
    carKnown = true;
  }

  // ---------- modes ----------
  let mode = 'edit';
  let trip = { km: 0, score: 100, areaM2: 0 };
  const areaText = (m2) => (m2 < 100000 ? [fmt(Math.round(m2 / 10) * 10), 'm²'] : [(m2 / 1e6).toFixed(2), 'km²']);
  let ui = { view: 'home', tab: 'rec' };
  function setMode(m) {
    mode = m;
    document.body.className = m;
    $('edit').hidden = m !== 'edit';
    $('drive').hidden = m !== 'drive';
    $('saving').hidden = m !== 'saving';
    controls.enabled = m === 'edit';
    car.visible = m === 'drive' && carKnown;
    if (m !== 'edit') { dropPreview(); highlight(null); ui = { view: 'home', tab: 'rec' }; }
    if (m === 'drive') { follow = true; tween = null; camHeading = null; headingFresh = true; trip = { km: 0, score: 100, areaM2: 0 }; renderDrive(); }
    if (m === 'edit') { follow = false; render(); overview(); }
    if (m !== 'edit') render();
  }

  function renderDrive() {
    const [num, unit] = areaText(trip.areaM2 || 0);
    $('dNew').innerHTML = `${num}<span style="font-size: 30px; margin-left: 6px">${unit}</span>`;
    $('dKm').textContent = `${trip.km.toFixed(1)} km`;
    $('dScore').textContent = trip.score;
  }
  let toastTimer = 0;
  function toast(msg) { $('toastText').textContent = msg; $('toast').style.opacity = 1; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').style.opacity = 0; }, 3000); }

  // ---------- edit panels ----------
  function toHome() { const away = ui.view !== 'home' && ui.view !== 'summary'; dropPreview(); highlight(null); ui = { view: 'home', tab: ui.tab || 'rec' }; if (away) goTo(controls.target.clone(), 70); render(); } // step back where the user is, not across the city
  const ic = (name, bg = 'var(--leaf-soft)', fg = '#2F8F55', size = 44) => `<span class="ic" style="width:${size}px;height:${size}px;background:${bg};color:${fg}">${svg(ICON[name], Math.round(size / 2), 2.2)}</span>`;
  const row = (act, icon, title, sub, extra = '') => `<button type="button" class="row" data-act="${act}" ${extra}>${icon}<span style="display:flex;flex-direction:column;flex-grow:1;min-width:0"><span class="round" style="font-size:19px">${title}</span>${sub ? `<span class="cap">${sub}</span>` : ''}</span></button>`;
  const pips = (n) => `<span class="pips" aria-hidden="true">${[1, 2, 3].map((i) => `<i class="${i <= n ? 'on' : ''}"></i>`).join('')}</span>`;
  // a small picture of a kind; dimmed while it is still locked
  const dexCell = (k, extra = '', off = false) => `<span class="dx${off ? ' off' : ''}" role="img" aria-label="${KINDS[k].name}${off ? ' (잠김)' : ''}">${THUMB[k] ? `<img src="${THUMB[k]}" alt="">` : svg(ICON[KINDS[k].icon], 22, 2)}${extra}</span>`;
  // one figure of the summary row: the number large with its unit, the label under it
  const fig = (label, num, unit, cls = '') => `<div><b class="round ${cls}">${num}<small>${unit}</small></b><span>${label}</span></div>`;
  // one figure of the trip summary: label, then the number large with its unit
  const tile = (label, num, unit) => `<div><span class="cap">${label}</span><b class="round">${num}<small>${unit}</small></b></div>`;
  // at most one row in the trip summary: four pictures, then how many more
  const oneRow = (cells) => (cells.length > 5 ? cells.slice(0, 4).join('') + `<span class="dx more round">+${cells.length - 4}</span>` : cells.join(''));
  function blockChips(b) {
    return `<div style="display:flex;gap:6px;flex-wrap:wrap">${b.lands.map((l) => `<span class="chip"><span class="dot" style="background:${LANDS[l].color}"></span>${LANDS[l].name}</span>`).join('')}<span class="chip">약 ${fmt(b.areaM2)}㎡</span>${b.water ? '<span class="chip blue">강가</span>' : ''}</div>`;
  }

  function render() {
    if (mode !== 'edit') { measureFree(); return; }
    renderPanel();
    measureFree();
  }
  // the bubble over the picked blocks: terrain and size; in move mode also why a block cannot take the item
  let tagIds = [];
  function updateTag() {
    const t = $('tag'), v = ui.view;
    const ids = v === 'block' ? ui.sel : v === 'move' ? ui.sel : v === 'preview' ? ui.preview.blocks : [];
    tagIds = ids || [];
    t.hidden = !tagIds.length;
    if (t.hidden) return;
    const g = groupOf(tagIds);
    let warn = '';
    if (v === 'move') { const it = S.items.find((i) => i.id === ui.item), st = status(it.kind, g, true); if (!st.ok) warn = st.badge; }
    t.classList.toggle('warn', !!warn);
    t.innerHTML = warn ? `<span class="round" style="font-size:20px">${warn}</span>`
      : `${g.lands.map((l) => `<span class="dot" style="background:${LANDS[l].color}"></span>`).join('')}<span class="round" style="font-size:22px">${landNames(g.lands)}</span><b>${fmt(g.areaM2)}㎡</b>`;
  }
  function renderHomeBtns() {
    const kinds = Object.keys(KINDS), open = kinds.filter(unlocked);
    $('dexBtn').innerHTML = `<span class="stack">${open.slice(-3).map((k) => `<img src="${THUMB[k] || ''}" alt="">`).join('')}</span><span class="round" style="font-size:24px">아이템</span><span class="round" style="font-size:20px;color:var(--ink-2)">${open.length}/${kinds.length}</span>`;
    $('dexBtn').setAttribute('aria-expanded', String(ui.view === 'dex'));
    $('invBtn').hidden = !S.inventory.length;
    $('invBtn').innerHTML = `${svg(ICON.box, 32)}<span class="n">${S.inventory.length}</span>`;
  }
  function renderPanel() {
    const p = $('panel'), v = ui.view;
    $('drawer').hidden = v !== 'block';
    document.body.classList.toggle('picking', v === 'block');
    document.body.dataset.view = v;
    $('demo').hidden = !(v === 'home' && !init.vehicle && bridge);
    $('homeBtns').hidden = !(v === 'home' || v === 'dex');
    refreshMiles();
    renderHomeBtns();
    updateTag();
    p.className = 'sheet';
    p.hidden = v === 'home' || v === 'block';

    if (v === 'summary') {
      const r = ui.summary, area = r.newM2 + (r.nearM2 || 0);
      const grown = r.grown.map((id) => S.items.find((i) => i.id === id)).filter(Boolean);
      const figs = [fig('주행 시간', r.minutes, '분'), fig('주행 거리', r.km.toFixed(1), 'km'), area > 0 ? fig('탐험 면적', ...areaText(area), 'area') : '', fig('안전 점수', r.score, '점')].filter(Boolean);
      p.classList.add('side');
      p.innerHTML = `
        <div class="sumhead"><span class="coin"></span>${r.withheld ? '<span class="round" style="font-size:34px">코인 지급 없음</span>' : `<span class="round">+${fmt(r.total)}</span><span class="round unit">코인</span>`}</div>
        ${r.withheld ? '<div class="cap">차량과 GPS의 주행거리 차이가 커서 코인을 지급하지 않았어요</div>' : ''}
        <div class="figs" style="grid-template-columns:repeat(${figs.length}, minmax(0, 1fr))">${figs.join('')}</div>
        ${r.unlocks.length ? `<div class="gain"><span class="round" style="font-size:20px">새 아이템</span><div class="dex">${oneRow(r.unlocks.map((k) => dexCell(k)))}</div></div>` : ''}
        ${grown.length ? `<div class="gain"><span class="round" style="font-size:20px">자랐어요</span><div class="dex">${oneRow(grown.map((it) => dexCell(it.kind, pips(stageOf(it)))))}</div></div>` : ''}
        <div style="flex-grow:1"></div>
        <button type="button" class="btn btn-p" data-act="home">지도 꾸미기</button>`;
      return;
    }

    if (v === 'dex') {
      const kinds = Object.keys(KINDS);
      p.classList.add('pop');
      p.innerHTML = `<div class="dex big">${kinds.map((k) => `<div class="dxi">${dexCell(k, '', !unlocked(k))}<span class="round">${unlocked(k) ? KINDS[k].name : `${FOUND_NAME[UNLOCK[k]]} 발견`}</span></div>`).join('')}</div>`;
      return;
    }

    if (v === 'block') { renderDrawer(groupOf(ui.sel)); return; }

    if (v === 'move') {
      const it = S.items.find((i) => i.id === ui.item), g = ui.sel.length ? groupOf(ui.sel) : null, st = g && status(it.kind, g, true);
      p.classList.add('strip');
      p.innerHTML = `${art(it.kind, 64)}<span class="round" style="font-size:24px">${KINDS[it.kind].name}</span>
        <button type="button" class="btn btn-p" data-act="moveHere" ${g && st.ok ? '' : 'disabled'}>여기로 옮기기</button>
        <button type="button" class="btn btn-s" data-act="item" data-id="${it.id}">취소</button>`;
      return;
    }

    if (v === 'preview') {
      const pv = ui.preview, b = groupOf(pv.blocks), K = KINDS[pv.kind];
      const price = pv.fromInv != null ? 0 : priceOf(pv.kind, b);
      p.classList.add('strip');
      p.innerHTML = `${art(pv.kind, 64)}<span class="round" style="font-size:24px">${K.name}</span>
        <span class="round" style="font-size:22px;display:flex;align-items:center;gap:6px">${price ? `<span class="coin"></span>${fmt(price)}` : '무료'}</span>
        <button type="button" class="btn btn-p" data-act="confirm">배치하기</button>
        <button type="button" class="btn btn-s" data-act="reroll">${svg(ICON.shuffle, 20)}배치 섞기</button>
        <button type="button" class="btn btn-s" data-act="cancel">취소</button>`;
      return;
    }

    if (v === 'item') {
      const it = S.items.find((i) => i.id === ui.item);
      if (!it) { toHome(); return; }
      const K = KINDS[it.kind], st = stageOf(it);
      p.classList.add('float');
      p.innerHTML = `
        <div style="display:flex;align-items:center;gap:14px">${art(it.kind, 72)}<div style="flex-grow:1;min-width:0;display:flex;flex-direction:column;gap:8px"><span class="round" style="font-size:28px;line-height:1">${K.name}</span>
          <div class="grow" role="img" aria-label="성장 ${st}단계">${pips(st)}<div class="bar" style="flex-grow:1"><i style="width:${Math.round(growthOf(it) * 100)}%"></i></div></div></div>
          <button type="button" class="icon-btn" style="align-self:flex-start;width:44px;height:44px" data-act="home" aria-label="닫기">${svg(ICON.close, 20)}</button></div>
        <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px">
          <button type="button" class="btn btn-s sm" style="font-size:16px;flex-direction:column;gap:2px;height:76px;padding:0" data-act="move">${svg(ICON.move, 22)}옮기기</button>
          <button type="button" class="btn btn-s sm" style="font-size:16px;flex-direction:column;gap:2px;height:76px;padding:0" data-act="reshape">${svg(ICON.shuffle, 22)}배치 섞기</button>
          <button type="button" class="btn btn-s sm" style="font-size:16px;flex-direction:column;gap:2px;height:76px;padding:0" data-act="store">${svg(ICON.box, 22)}보관하기</button>
        </div>`;
    }
  }

  function renderDrawer(b) {
    const tabs = CATS.filter(([id]) => id !== 'inv' || S.inventory.length);
    if (!tabs.some(([id]) => id === ui.tab)) ui.tab = 'rec';
    $('tabs').innerHTML = tabs.map(([id, label]) => `<button type="button" class="tab" role="tab" aria-selected="${id === ui.tab}" data-tab="${id}">${label}</button>`).join('');
    let list;
    if (ui.tab === 'inv') list = S.inventory.map((it2, i) => ({ kind: it2.kind, inv: i }));
    else list = Object.keys(KINDS).filter((k) => (ui.tab === 'rec' ? status(k, b).ok : KINDS[k].cat === ui.tab)).map((k) => ({ kind: k }));
    if (ui.tab === 'rec') list.sort((x, y) => (status(y.kind, b).fit ? 1 : 0) - (status(x.kind, b).fit ? 1 : 0));
    $('cards').innerHTML = list.length ? list.map((c) => {
      const K = KINDS[c.kind], st = status(c.kind, b, c.inv != null);
      const on = c.inv != null ? ui.fromInv === c.inv : ui.fromInv == null && ui.kind === c.kind;
      const meta = c.inv != null ? pips(stageOf(S.inventory[c.inv])) : K.price ? fmt(priceOf(c.kind, b)) : '무료';
      if (on) {
        const cost = c.inv != null || !K.price ? '' : `<span class="coin"></span>${fmt(priceOf(c.kind, b))} `;
        return `<div class="card wide" role="group" aria-label="${K.name}" data-kind="${c.kind}" ${c.inv != null ? `data-inv="${c.inv}"` : ''}>${art(c.kind, 120, 112)}<div class="wbody"><span class="nm">${K.name}</span>${st.ok ? '' : `<span class="badge">${st.badge}</span>`}<button type="button" class="btn btn-p cta" data-act="preview" ${st.ok ? '' : 'disabled'}>${cost}배치</button></div></div>`;
      }
      return `<button type="button" class="card ${st.ok ? '' : 'dim'}" aria-pressed="${on}" data-kind="${c.kind}" ${c.inv != null ? `data-inv="${c.inv}"` : ''}>${art(c.kind, 136, 84)}<span class="top"><span class="nm">${K.name}</span><span class="price">${c.inv != null || !K.price ? '' : '<span class="coin"></span>'}${meta}</span></span>${!st.ok || st.fit ? `<span class="badge ${st.fit ? 'ok' : ''}">${st.badge}</span>` : ''}</button>`;
    }).join('') : '<div class="cap" style="padding:20px">지금 배치할 수 있는 아이템이 없어요.</div>';
  }

  $('tabs').addEventListener('click', (e) => { const t = e.target.closest('[data-tab]'); if (!t) return; ui.tab = t.dataset.tab; render(); });
  $('cards').addEventListener('click', (e) => {
    const a = e.target.closest('[data-act]'); if (a) { if (!a.disabled) act(a.dataset.act, a.dataset); return; }
    const c = e.target.closest('[data-kind]'); if (!c) return;
    if (c.dataset.inv != null) { ui.fromInv = Number(c.dataset.inv); ui.kind = null; } else { ui.kind = c.dataset.kind; ui.fromInv = null; }
    render();
  });
  $('panel').addEventListener('click', (e) => { const t = e.target.closest('[data-act]'); if (t && !t.disabled) act(t.dataset.act, t.dataset); });
  $('demo').addEventListener('click', () => { if (bridge) bridge.startDemo(); });
  $('drawerX').innerHTML = svg(ICON.close, 20);
  $('drawerX').addEventListener('click', () => act('home'));
  $('dexBtn').addEventListener('click', () => { if (ui.view === 'dex') return toHome(); ui = { view: 'dex', tab: ui.tab }; render(); });
  $('invBtn').addEventListener('click', () => act('openInv'));

  // ---------- actions ----------
  function dropPreview() { if (built.has('preview')) unmake('preview'); }
  function showPreview() {
    dropPreview();
    const pv = ui.preview;
    const v = make({ id: 'preview', kind: pv.kind, blocks: pv.blocks, seed: pv.seed, km: pv.km });
    built.set('preview', v);
    animals.forEach((a) => { if (!a.goal) { a.goal = a.obj.position.clone(); a.wait = Math.random() * 2; a.obj.userData.baseY = a.obj.userData.body.position.y; } });
  }
  function selectItem(id) {
    const it = S.items.find((i) => i.id === id); if (!it) return;
    dropPreview();
    ui = { view: 'item', item: id, tab: ui.tab };
    highlight(it.blocks);
    render();
    focusBlocks(it.blocks); // after render, so the open map area is measured with this panel
  }
  // select a range of empty blocks; a drag calls this again as the range grows, so the chosen item is kept while it still fits
  function selectBlocks(ids, focus) {
    if (ui.view === 'move') { ui.sel = ids; highlight(ids); render(); return; }
    const g = groupOf(ids);
    const keepInv = ui.view === 'block' && ui.fromInv != null ? ui.fromInv : ui.openInv ? 0 : null;
    const was = ui.view === 'block' ? ui.kind : null;
    ui = { view: 'block', sel: ids, tab: keepInv != null ? 'inv' : 'rec', kind: null, fromInv: keepInv }; // a new block opens on 추천, where the item chosen for it is shown; 보관함 only while taking a stored item out
    if (keepInv == null) { const ks = Object.keys(KINDS); ui.kind = was && status(was, g).ok ? was : ks.find((k) => status(k, g).fit) || ks.find((k) => status(k, g).ok) || null; }
    highlight(ids);
    render();
    if (focus) focusBlocks(ids, 2.2); // wide enough to see the neighbours a drag can reach
  }
  function act(a, data = {}) {
    if (a === 'home') return toHome();
    if (a === 'item') return selectItem(Number(data.id));
    if (a === 'firstEmpty' || a === 'openInv') {
      // a roomy block near what the user is looking at; green ones count as twice as close
      const t = controls.target, far = (b) => Math.hypot((b.bbox[0] + b.bbox[2]) / 20 - 64 - t.x, (b.bbox[1] + b.bbox[3]) / 20 - 36 - t.z) / (isGreen(b) ? 2 : 1);
      const free = [...revealed].map((i) => blocks.get(i)).filter((b) => plot(b) && !itemAt(b.id));
      const fit = free.filter((b) => b.areaM2 >= 1500 && b.areaM2 < 30000);
      const id = (fit.length ? fit : free).sort((x, y) => far(x) - far(y))[0]?.id;
      if (id == null) { note('빈 구역이 없어요. 주행해서 새 구역을 찾아보세요.'); return; }
      if (a === 'openInv') ui.openInv = true;
      return selectBlocks([id], true);
    }
    if (a === 'preview') {
      const inv = ui.fromInv != null ? S.inventory[ui.fromInv] : null;
      ui = { ...ui, view: 'preview', preview: { kind: inv ? inv.kind : ui.kind, blocks: ui.sel, seed: (Math.random() * 1e9) | 0, km: inv ? inv.km : 0, fromInv: ui.fromInv } };
      showPreview(); render(); return;
    }
    if (a === 'reroll') { ui.preview.seed = (Math.random() * 1e9) | 0; showPreview(); render(); return; }
    if (a === 'cancel') { dropPreview(); ui.view = 'block'; render(); return; }
    if (a === 'confirm') {
      const pv = ui.preview, b = groupOf(pv.blocks), K = KINDS[pv.kind];
      const price = pv.fromInv != null ? 0 : priceOf(pv.kind, b);
      if (!spend(price)) { note('코인이 부족해요'); return; }
      dropPreview();
      const id = S.nextId;
      const fromInv = pv.fromInv != null ? S.inventory[pv.fromInv] : null;
      commit(() => {
        S.nextId++;
        S.items.push({ id, kind: pv.kind, blocks: pv.blocks, seed: pv.seed, km: pv.km });
        if (pv.fromInv != null) S.inventory.splice(pv.fromInv, 1);
      });
      return selectItem(id);
    }
    if (a === 'reshape') { const it = S.items.find((i) => i.id === ui.item); commit(() => { it.seed = (Math.random() * 1e9) | 0; }); render(); return; }
    if (a === 'store') {
      const it = S.items.find((i) => i.id === ui.item);
      commit(() => { S.items = S.items.filter((i) => i.id !== it.id); S.inventory.push({ kind: it.kind, km: it.km }); });
      note(`${KINDS[it.kind].name}${eul(KINDS[it.kind].name)} 보관함에 넣었어요`);
      return toHome();
    }
    if (a === 'move') { ui.view = 'move'; ui.sel = []; highlight(null); render(); return; }
    if (a === 'moveHere') {
      const it = S.items.find((i) => i.id === ui.item), dest = ui.sel;
      commit(() => { it.blocks = dest; });
      note(`${KINDS[it.kind].name}${eul(KINDS[it.kind].name)} 옮겼어요`);
      return selectItem(it.id);
    }
  }

  // ---------- picking ----------
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2(), plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0), hitP = new THREE.Vector3();
  const pip = (poly, x, y) => { let c = false; for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) { const [xi, yi] = poly[i], [xj, yj] = poly[j]; if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c; } return c; };
  function pickAt(e) {
    const r = renderer.domElement.getBoundingClientRect();
    ndc.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    const groups = [...built].filter(([id]) => id !== 'preview').map(([, v]) => v.g);
    const hit = ray.intersectObjects(groups, true)[0];
    if (hit) return { item: hit.object.userData.item };
    const th = ray.intersectObjects([...shown.values()].flatMap((t) => t.towerMeshes), false)[0];
    if (th && towerBlockAt(th) != null) return { block: towerBlockAt(th) };
    if (!ray.ray.intersectPlane(plane, hitP)) return {};
    const px = (hitP.x + 64) * 10, py = (hitP.z + 36) * 10;
    for (const id of revealed) { const b = blocks.get(id); if (b && pip(b.edge, px, py)) { const it = itemAt(id); return it ? { item: it.id } : { block: id }; } }
    return {};
  }
  // one finger on an empty block selects like table cells: a tap picks that block, a drag picks the range from where it
  // started to where the finger is. A drag that starts anywhere else, or two fingers, still moves the camera.
  const canPick = (id, own) => { const b = blocks.get(id), it = itemAt(id); return plot(b) && (!it || it.id === own); };
  const toScreen = (x, y) => { tmp.copy(W(x, y)).project(camera); return [((tmp.x + 1) / 2) * innerWidth, ((1 - tmp.y) / 2) * innerHeight]; };
  function rangeIds(g, e) {
    const x0 = Math.min(g.x, e.clientX), x1 = Math.max(g.x, e.clientX), y0 = Math.min(g.y, e.clientY), y1 = Math.max(g.y, e.clientY);
    const h = pickAt(e), at = new Set([g.id0]);
    if (h.block != null && canPick(h.block, g.own)) at.add(h.block);
    const hits = [];
    revealed.forEach((id) => {
      if (at.has(id) || !canPick(id, g.own)) return;
      const b = blocks.get(id);
      const [sx, sy] = toScreen(b.isle.reduce((t, p) => t + p[0], 0) / b.isle.length, b.isle.reduce((t, p) => t + p[1], 0) / b.isle.length);
      if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) hits.push([Math.hypot(sx - g.x, sy - g.y), id]);
    });
    hits.sort((a, b) => a[0] - b[0]); // past the limit, the blocks nearest to where the drag began stay
    const ids = [...at, ...hits.map((h2) => h2[1])];
    if (ids.length > MAX_SEL) g.capped = true;
    return ids.slice(0, MAX_SEL).sort((a, b) => a - b);
  }
  let downAt = null, gest = null;
  const pickView = () => ui.view === 'home' || ui.view === 'dex' || ui.view === 'block' || ui.view === 'move';
  renderer.domElement.addEventListener('pointerdown', (e) => {
    if (!e.isPrimary) { if (gest) { gest = null; controls.enabled = mode === 'edit'; } return; } // a second finger: zoom or pan instead
    downAt = [e.clientX, e.clientY];
    if (mode !== 'edit' || !pickView()) return;
    const own = ui.view === 'move' ? ui.item : null, h = pickAt(e);
    const id = h.block != null ? h.block : null;
    if (id == null || !canPick(id, own)) return;
    gest = { x: e.clientX, y: e.clientY, id0: id, own, moved: false, key: '' };
    controls.enabled = false; // this finger selects; it does not turn the map
  }, { capture: true }); // runs before the camera controls see the same press
  renderer.domElement.addEventListener('pointermove', (e) => {
    if (!gest || !e.isPrimary) return;
    if (!gest.moved && Math.hypot(e.clientX - gest.x, e.clientY - gest.y) < 10) return;
    gest.moved = true;
    const ids = rangeIds(gest, e), key = ids.join(',');
    if (key !== gest.key) { gest.key = key; selectBlocks(ids, false); }
  });
  renderer.domElement.addEventListener('pointerup', (e) => {
    if (!e.isPrimary) return;
    const g = gest; gest = null;
    if (mode === 'edit') controls.enabled = true;
    if (g && g.moved) { if (g.capped) note(`한 번에 ${MAX_SEL}곳까지 고를 수 있어요`); return; }
    if (mode !== 'edit' || !downAt || Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) > 8) return;
    if (ui.view === 'preview') return; // finish or cancel the current step first
    const h = pickAt(e);
    if (ui.view === 'move') {
      const it = S.items.find((i) => i.id === ui.item);
      if (h.block == null && h.item !== it.id) { note('비어 있는 구역을 눌러 주세요'); return; }
      if (h.block != null) selectBlocks([h.block], false);
      return;
    }
    if (h.block != null && ui.view === 'block' && ui.sel.length > 1 && ui.sel.includes(h.block)) {
      return selectBlocks(ui.sel.filter((id) => id !== h.block), false); // tapping a block in the range takes it out
    }
    if (h.item != null) selectItem(h.item);
    else if (h.block != null) {
      if (!blocks.get(h.block).isle) { note('구역이 너무 좁아 꾸밀 수 없어요'); return; }
      if (!plot(blocks.get(h.block))) { note('구역이 너무 넓어 꾸밀 수 없어요'); return; }
      selectBlocks([h.block], true);
    } else if (ui.view !== 'home') toHome();
  });
  renderer.domElement.addEventListener('pointercancel', () => { gest = null; if (mode === 'edit') controls.enabled = true; });

  // ---------- messages from the app ----------
  // Android back: step back one panel; false lets the app close
  window.__back = () => {
    if (mode !== 'edit' || ui.view === 'home') return false; // the Android back key leaves the app from the home view
    toHome();
    return true;
  };
  window.__cartopia = (e) => {
    if (e.t === 'mode') setMode(e.mode);
    else if (e.t === 'car') { moveCar(e.x, e.y); if (mode === 'drive') car.visible = true; }
    else if (e.t === 'reveal') {
      e.ids.forEach((id) => revealed.add(id)); noteToday(e.ids);
      loadBlocks(e.ids).then(() => { touchTiles(e.ids); noteFound(e.ids); save(); });
      if (mode !== 'drive') render();
    }
    else if (e.t === 'trip') { trip = e; renderDrive(); }
    else if (e.t === 'miles') refreshMiles();
    else if (e.t === 'summary') {
      const grown = applyTrips(bridge ? JSON.parse(bridge.takeTrips()) : []);
      const unlocks = newUnlocks();
      save(); syncScene();
      setMode('edit');
      ui = { view: 'summary', tab: 'rec', summary: { ...e, grown, unlocks } };
      render();
      overviewAll();
    }
  };

  // ---------- resize + loop ----------
  // The camera's centre is moved to the middle of the map area that the top bar, panel and drawer leave open,
  // so the block the camera goes to is seen instead of sitting under the drawer.
  const viewOff = { x: 0, y: 0, tx: 0, ty: 0, fh: 0 };
  // The bubble sits over the top of the picked blocks and the item card beside the item, wherever the camera goes.
  const proj = new THREE.Vector3();
  const toPx = (v) => { proj.copy(v).project(camera); return [((proj.x + 1) / 2) * innerWidth, ((1 - proj.y) / 2) * innerHeight]; };
  function placeFloats() {
    if (mode !== 'edit') return;
    const t = $('tag');
    if (!t.hidden && tagIds.length) {
      const bs = tagIds.map((id) => blocks.get(id)).filter(Boolean);
      if (bs.length) {
        const x0 = Math.min(...bs.map((b) => b.bbox[0])), y0 = Math.min(...bs.map((b) => b.bbox[1])), x1 = Math.max(...bs.map((b) => b.bbox[2])), y1 = Math.max(...bs.map((b) => b.bbox[3]));
        const pts = [[x0, y0], [x1, y0], [x0, y1], [x1, y1]].map(([x, y]) => toPx(W(x, y)));
        const sx = (Math.min(...pts.map((q) => q[0])) + Math.max(...pts.map((q) => q[0]))) / 2, sy = Math.min(...pts.map((q) => q[1]));
        t.style.left = `${clamp(sx, t.offsetWidth / 2 + 12, innerWidth - t.offsetWidth / 2 - 12)}px`;
        t.style.top = `${clamp(sy - 12, 150, innerHeight - 260)}px`;
      }
    }
    const p = $('panel');
    if (p.hidden || !p.classList.contains('float') || innerWidth <= 900) { p.style.left = p.style.top = ''; return; }
    const v = built.get(ui.item); if (!v) return;
    const [sx, sy] = toPx(tmp.set(v.at.x, v.h * 0.45, v.at.z));
    const w = p.offsetWidth, h = p.offsetHeight;
    let left = sx + 80, flip = false;
    if (left + w > innerWidth - 16) { left = sx - 80 - w; flip = true; }
    p.classList.toggle('flip', flip);
    p.style.left = `${clamp(left, 16, innerWidth - w - 16)}px`;
    p.style.top = `${clamp(sy - h / 2, 96, innerHeight - h - 16)}px`;
  }
  function measureFree() {
    const w = innerWidth, hh = innerHeight;
    viewOff.fh = hh;
    if (mode !== 'edit') { viewOff.tx = viewOff.ty = 0; return; }
    const f = { l: 0, r: w, t: $('top').getBoundingClientRect().bottom, b: hh };
    ['panel', 'drawer'].forEach((id) => {
      const el = $(id); if (el.hidden) return;
      if (id === 'panel' && !(el.classList.contains('side') || el.classList.contains('strip'))) return; // the floating card and the item list do not push the map
      if (id === 'panel' && el.classList.contains('strip')) { f.b = Math.min(f.b, el.getBoundingClientRect().top); return; }
      const r = el.getBoundingClientRect(); if (!r.width || !r.height) return;
      if (r.width > w * 0.6) { if ((r.top + r.bottom) / 2 < (f.t + f.b) / 2) f.t = Math.max(f.t, r.bottom); else f.b = Math.min(f.b, r.top); }
      else if ((r.left + r.right) / 2 > w / 2) f.r = Math.min(f.r, r.left); else f.l = Math.max(f.l, r.right);
    });
    viewOff.fh = f.b - f.t;
    if (f.b - f.t < 60) { viewOff.tx = viewOff.ty = 0; return; }
    viewOff.tx = w / 2 - (f.l + f.r) / 2; viewOff.ty = hh / 2 - (f.t + f.b) / 2;
  }
  function applyViewOff(k) {
    const x = viewOff.x + (viewOff.tx - viewOff.x) * k, y = viewOff.y + (viewOff.ty - viewOff.y) * k;
    if (Math.abs(x - viewOff.x) < 0.3 && Math.abs(y - viewOff.y) < 0.3 && k < 1) return;
    viewOff.x = x; viewOff.y = y;
    const w = stage.clientWidth, hh = stage.clientHeight;
    camera.setViewOffset(w, hh, x, y, w, hh);
    camera.updateProjectionMatrix();
  }
  function resize() {
    const w = stage.clientWidth, hh = stage.clientHeight;
    renderer.setSize(w, hh, false);
    camera.aspect = w / hh;
    measureFree();
    applyViewOff(1);
  }
  window.addEventListener('resize', resize);
  resize();

  const clock = new THREE.Clock();
  const tmp = new THREE.Vector3();
  const CAM_BACK = 20, CAM_UP = 22, CAM_AHEAD = 7; // world units (about 19 m each): behind and above the car, looking past it
  const turnToward = (a, b, k) => a + (Math.atan2(Math.sin(b - a), Math.cos(b - a)) * k);
  function place(el, at, y) {
    tmp.copy(at); tmp.y = y; tmp.project(camera);
    const vis = tmp.z < 1 && Math.abs(tmp.x) < 1.05 && Math.abs(tmp.y) < 1.05;
    el.style.display = vis ? '' : 'none';
    if (vis) el.style.transform = `translate(${((tmp.x + 1) / 2) * innerWidth}px, ${((1 - tmp.y) / 2) * innerHeight}px)${el.classList.contains('tag') ? ' translate(-50%, -100%)' : ''}`;
  }
  function tick() {
    const raw = clock.getDelta(), dt = Math.min(raw, 0.05);
    const t = clock.elapsedTime;
    if (mode === 'drive' && carKnown) {
      // easing uses the real frame time (up to 1 s) so a slow head unit does not leave the camera trailing the car
      const dt = Math.min(raw, 1);
      // heading-up like a car navigation screen: the car always points up the screen and the map turns under it
      car.position.lerp(carGoal, 1 - Math.exp(-dt * 4));
      carHeading = turnToward(carHeading, goalHeading, 1 - Math.exp(-dt * 6));
      car.rotation.y = -carHeading;
      camHeading = camHeading === null ? carHeading : turnToward(camHeading, carHeading, 1 - Math.exp(-dt * 2.2));
      const fx = Math.cos(camHeading), fz = Math.sin(camHeading);
      const k = 1 - Math.exp(-dt * 2.5);
      controls.target.lerp(tmp.set(car.position.x + fx * CAM_AHEAD, 0, car.position.z + fz * CAM_AHEAD), k);
      camera.position.lerp(tmp.set(car.position.x - fx * CAM_BACK, CAM_UP, car.position.z - fz * CAM_BACK), k);
    } else if (tween) {
      tween.t = Math.min(1, tween.t + dt / 1.1);
      applyViewOff(1 - Math.pow(1 - tween.t, 3));
      const k = 1 - Math.pow(1 - tween.t, 3);
      camera.position.lerpVectors(tween.fromPos, tween.toPos, k);
      controls.target.lerpVectors(tween.fromTarget, tween.toTarget, k);
      if (tween.t >= 1) tween = null;
    }
    if (!tween) applyViewOff(reduce ? 1 : 1 - Math.exp(-dt * 6));
    placeFloats();
    const camD = camera.position.distanceTo(controls.target);
    scene.fog.near = Math.max(90, camD * 1.3); scene.fog.far = Math.max(190, camD * 2.7);
    if (camera.far < camD * 3) { camera.far = camD * 3; camera.updateProjectionMatrix(); }
    if (!reduce) {
      animals.forEach((a, i) => {
        const o = a.obj, u = o.userData;
        if (a.wait > 0) {
          a.wait -= dt;
          u.head.rotation.z = -0.45 * Math.max(0, Math.sin(t * 2 + i));
          u.legs.forEach((l) => { l.rotation.z *= 0.8; });
          u.body.position.y = u.baseY;
          if (a.wait <= 0) {
            const p = randomInside(a.area, 0.9);
            // only accept goals whose straight path stays inside the block
            const ok = p && [0.25, 0.5, 0.75].every((f) => inside(a.area, o.position.x + (p.x - o.position.x) * f, o.position.z + (p.z - o.position.z) * f));
            if (ok) a.goal.set(p.x, o.position.y, p.z); else a.wait = 0.5;
          }
          return;
        }
        tmp.copy(a.goal).sub(o.position);
        const d = tmp.length();
        if (d < 0.1) { a.wait = 1.5 + Math.random() * 3; return; }
        const want = Math.atan2(-tmp.z, tmp.x);
        let diff = want - o.rotation.y;
        diff = Math.atan2(Math.sin(diff), Math.cos(diff));
        o.rotation.y += diff * Math.min(1, dt * 4);
        o.position.addScaledVector(tmp.normalize(), Math.min(d, a.speed * dt));
        u.head.rotation.z *= 0.9;
        const step = Math.sin(t * 12 + i);
        u.legs.forEach((l, j) => { l.rotation.z = (j % 2 ? 1 : -1) * step * 0.35; });
        u.body.position.y = u.baseY + Math.abs(step) * 0.08; // little hop while walking
      });
      swayers.forEach((s, i) => { const tr = s.obj; tr.userData.sway.rotation.z = Math.sin(t * 1.1 + i) * 0.035; tr.userData.sway.rotation.x = Math.cos(t * 0.9 + i) * 0.025; });
      docks.forEach(({ obj }) => {
        const dk = obj.userData;
        dk.bobber.position.y = 0.15 + Math.sin(t * 3.2) * 0.06 + (Math.sin(t * 0.7) > 0.85 ? -0.12 : 0);
        const rp = (t * 0.6) % 1;
        dk.ripple.scale.setScalar(0.6 + rp * 1.6);
        dk.ripple.material.opacity = 0.6 * (1 - rp);
        dk.boat.position.y = 0.25 + Math.sin(t * 1.4) * 0.05;
        dk.boat.rotation.z = Math.sin(t * 1.1) * 0.04;
      });
    }
    controls.update();
    sun.target.position.copy(controls.target); sun.position.copy(controls.target).add(SUN_OFF);
    sea.position.x = shadowCatcher.position.x = controls.target.x; sea.position.z = shadowCatcher.position.z = controls.target.z;
    updateTiles(); paintDirty();
    renderer.render(scene, camera);
    requestAnimationFrame(tick);
  }

  await loadBlocks(S.items.flatMap((it) => it.blocks));
  S.items = S.items.filter((it) => it.blocks.every((id) => blocks.has(id))); // items on blocks this map build no longer has
  if (bridge) { const late = JSON.parse(bridge.takeTrips()); if (late.length) applyTrips(late); }
  // terrain found so far; the first run on these rules reads every revealed tile once to catch up
  noteFound([...revealed]);
  if (!S.found || !S.seen) {
    S.found = S.found || [];
    loadBlocks([...revealed]).then(() => { noteFound([...revealed]); if (!S.seen) newUnlocks(); save(); if (mode === 'edit') render(); });
  }
  makeThumbs();
  syncScene();
  save();
  if (init.car) moveCar(init.car.x, init.car.y);
  setMode(init.mode || 'edit');
  if (init.mode === 'drive' && init.trip) { trip = init.trip; renderDrive(); }
  if (init.summary) window.__cartopia({ ...init.summary, t: 'summary' }); // a drive saved while the page was closed
  tick();
})();
