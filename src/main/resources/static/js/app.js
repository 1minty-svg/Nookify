// ========================================
// NOOKIFY APP - WITH GLTF SUPPORT & BACKEND INTEGRATION
// ========================================

const CONFIG = {
    modelsBaseUrl: 'http://localhost:8080/api/models',
    generateUrl: 'http://localhost:8080/api/furniture/plan',
    useBackend: true,
    timeout: 10000
};

let currentExportSettings = {
    geometry: 'Low Poly',
    format: 'fbx',
    material: 'Shaded',
    resolution: '2K'
};

let currentSceneModels = [];
let savedScenes = new Map();

function loadSavedScenes() {
    const saved = localStorage.getItem('nookify_saved_scenes');
    if (saved) {
        try {
            const parsed = JSON.parse(saved);
            savedScenes = new Map(parsed);
            console.log(`📦 Loaded ${savedScenes.size} saved scenes`);
        } catch(e) { console.warn('Failed to load saved scenes'); }
    }
}

function saveSceneToStorage(cardIndex, sceneData) {
    savedScenes.set(cardIndex, sceneData);
    try {
        localStorage.setItem('nookify_saved_scenes', JSON.stringify([...savedScenes]));
    } catch (e) {
        console.warn('⚠️ localStorage quota exceeded, scene not persisted:', e.message);
    }
}

class SceneBuilder {
    constructor(containerId) {
        this.container = document.getElementById(containerId);
        if (!this.container) return;

        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0xf8fafc);

        this.camera = new THREE.PerspectiveCamera(45, this.container.clientWidth / this.container.clientHeight, 0.1, 100);
        this.camera.position.set(6, 5, 8);
        this.camera.lookAt(3, 0.5, 3);

        this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
        this.renderer.setSize(this.container.clientWidth, this.container.clientHeight);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.renderer.shadowMap.enabled = true;
        this.container.appendChild(this.renderer.domElement);

        this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.05;
        this.controls.target.set(3, 0.5, 3); // Центр 6x6 комнаты

        this._initLights();
        this._initFloor();
        this._animate();

        window.addEventListener('resize', () => this._onResize());
    }

    _initLights() {
        const ambient = new THREE.AmbientLight(0xffffff, 0.6);
        const dirLight = new THREE.DirectionalLight(0xffffff, 0.8);
        dirLight.position.set(5, 8, 5);
        dirLight.castShadow = true;
        dirLight.shadow.mapSize.set(1024, 1024);
        const fillLight = new THREE.PointLight(0x4466cc, 0.3);
        fillLight.position.set(-2, 2, 3);
        this.scene.add(ambient, dirLight, fillLight);
    }

    _initFloor() {
        const grid = new THREE.GridHelper(10, 20, 0x0066cc, 0xcbd5e1);
        grid.material.transparent = true;
        grid.material.opacity = 0.35;
        grid.position.y = -0.01;
        grid.userData = { isGrid: true };
        this.scene.add(grid);

        // Комната 6x6, смещаем центр, чтобы комната начиналась от (0,0) до (6,6)
        const planeMat = new THREE.MeshStandardMaterial({ color: 0xf1f5f9, roughness: 0.7 });
        const plane = new THREE.Mesh(new THREE.PlaneGeometry(6, 6), planeMat);
        plane.rotation.x = -Math.PI / 2;
        plane.position.set(3, -0.02, 3);
        plane.receiveShadow = true;
        plane.userData = { isFloor: true };
        this.scene.add(plane);
    }

    clear() {
        if (!this.scene) return;
        const toRemove = [];
        this.scene.traverse(obj => {
            if (obj.userData?.modelId && !obj.userData?.isFloor && !obj.userData?.isGrid) {
                toRemove.push(obj);
            }
        });
        toRemove.forEach(obj => {
            this.scene.remove(obj);
            if (obj.geometry) obj.geometry.dispose();
            if (obj.material) {
                if (Array.isArray(obj.material)) obj.material.forEach(m => m.dispose());
                else obj.material.dispose();
            }
        });
    }

    async addModel(item) {
        if (!item) return null;

        const modelUrl = item.modelUrl || item.model_url || item.downloadUrl || item.url || item.modelPath || item.model_path;
        if (!modelUrl) {
            console.warn(`⚠️ Бэкенд не прислал URL для:`, item);
            return this._createFallbackBox(item);
        }

        console.log(`📥 Загрузка GLB: ${modelUrl}`);

        try {
            const loader = new THREE.GLTFLoader();
            const gltf = await new Promise((resolve, reject) => {
                loader.load(modelUrl, resolve, undefined, reject);
            });

            const model = gltf.scene;
            const box = new THREE.Box3().setFromObject(model);
            const size = new THREE.Vector3();
            box.getSize(size);

            const targetW = item.width ?? item.dimensions?.width ?? null;
            const targetH = item.height ?? item.dimensions?.height ?? null;
            const targetD = item.depth ?? item.dimensions?.depth ?? null;

            if (targetW && targetH && targetD && size.x > 0 && size.y > 0 && size.z > 0) {
                const scaleX = targetW / size.x;
                const scaleY = targetH / size.y;
                const scaleZ = targetD / size.z;
                const scale = Math.min(scaleX, scaleY, scaleZ);
                model.scale.setScalar(scale || 1);
            }

            const scaledBox = new THREE.Box3().setFromObject(model);
            const scaledSize = new THREE.Vector3();
            scaledBox.getSize(scaledSize);

            const px = item.x ?? item.pos_x ?? item.posX ?? item.position?.x ?? 0;
            const rawY = item.y ?? item.pos_y ?? item.posY ?? item.position?.y ?? null;
            const py = (rawY !== null && rawY !== 0) ? rawY : (scaledSize.y / 2);
            const pz = item.z ?? item.pos_z ?? item.posZ ?? item.position?.z ?? 0;

            model.position.set(px, py, pz);

            const rot = item.rotation ?? item.rotY ?? item.rot_y ?? 0;
            model.rotation.y = THREE.MathUtils.degToRad(rot);

            model.traverse(child => {
                if (child.isMesh) {
                    child.castShadow = true;
                    child.receiveShadow = true;
                }
            });

            model.userData = {
                modelId: item.model_id || item.id || 'minio_model',
                modelName: item.name || item.modelName || 'Object',
                category: item.category || 'FURNITURE',
                dimensions: { width: targetW || size.x, height: targetH || size.y, depth: targetD || size.z }
            };

            this.scene.add(model);
            return model;
        } catch (err) {
            console.warn(`⚠️ Ошибка загрузки (${err.message}). Создаю куб-заглушку.`);
            return this._createFallbackBox(item);
        }
    }

    _createFallbackBox(item) {
        const w = item.width || item.dimensions?.width || 0.8;
        const h = item.height || item.dimensions?.height || 0.8;
        const d = item.depth || item.dimensions?.depth || 0.8;

        const geo = new THREE.BoxGeometry(w, h, d);
        const mat = new THREE.MeshStandardMaterial({ color: 0x94a3b8, roughness: 0.6 });
        const box = new THREE.Mesh(geo, mat);
        box.castShadow = true;
        box.receiveShadow = true;

        const px = item.x ?? item.pos_x ?? item.posX ?? item.position?.x ?? 0;
        const py = item.y ?? item.pos_y ?? item.posY ?? item.position?.y ?? (h / 2);
        const pz = item.z ?? item.pos_z ?? item.posZ ?? item.position?.z ?? 0;
        box.position.set(px, py, pz);

        const rot = item.rotation ?? item.rotY ?? item.rot_y ?? 0;
        box.rotation.y = THREE.MathUtils.degToRad(rot);

        box.userData = {
            modelId: item.model_id || item.id || 'fallback',
            modelName: item.name || 'Fallback Box',
            category: item.category || 'UNKNOWN',
            dimensions: { width: w, height: h, depth: d }
        };
        this.scene.add(box);
        return box;
    }

    async restoreScene(modelsData, updateCallback = null) {
        this.clear();
        for (const modelData of modelsData) {
            const model = await this.addModel(modelData);
            if (model && modelData.position) {
                model.position.set(modelData.position.x, modelData.position.y, modelData.position.z);
                model.rotation.y = THREE.MathUtils.degToRad(modelData.rotation || 0);
            }
        }
        if (updateCallback) updateCallback(modelsData);
    }

    _animate() {
        requestAnimationFrame(() => this._animate());
        if (this.controls) this.controls.update();
        if (this.renderer && this.scene && this.camera) {
            this.renderer.render(this.scene, this.camera);
        }
    }

    _onResize() {
        if (!this.container || !this.camera || !this.renderer) return;
        const w = this.container.clientWidth;
        const h = this.container.clientHeight;
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(w, h);
    }

    getCurrentSceneData() {
        const models = [];
        this.scene.traverse(obj => {
            if (obj.userData?.modelId && !obj.userData?.isFloor && !obj.userData?.isGrid) {
                models.push({
                    id: obj.userData.modelId,
                    name: obj.userData.modelName,
                    category: obj.userData.category,
                    model_url: obj.userData.modelUrl, // Сохраняем url для восстановления
                    dimensions: obj.userData.dimensions,
                    position: { x: obj.position.x, y: obj.position.y, z: obj.position.z },
                    rotation: THREE.MathUtils.radToDeg(obj.rotation.y)
                });
            }
        });
        return models;
    }

    captureScreenshot() {
        return new Promise((resolve) => {
            if (this.controls) this.controls.update();
            this.renderer.render(this.scene, this.camera);
            setTimeout(() => {
                this.renderer.render(this.scene, this.camera);
                const canvas = this.renderer.domElement;
                resolve(canvas.toDataURL('image/png'));
            }, 100);
        });
    }
}

class NookifyApp {
    constructor() {
        this.viewer = null;
        this.currentPrompt = '';
    }

    async init() {
        loadSavedScenes();
        this._initViewer();
        this._bindUI();
        this._bindExportSettings();
        this._fixImageErrors();
        this._restoreLikes();
        this._restoreGeneratedCards();
        console.log('🚀 Nookify Frontend Ready');
    }

    _restoreGeneratedCards() {
        for (const [cardIndex, sceneData] of savedScenes) {
            this._createGeneratedCard(sceneData.prompt, sceneData.screenshot, cardIndex, sceneData.modelsData);
        }
    }

    _restoreLikes() {
        document.querySelectorAll('.card').forEach((card, idx) => {
            if (!card.hasAttribute('data-card-index')) card.setAttribute('data-card-index', idx);
            const heart = card.querySelector('.card-heart');
            if (heart && localStorage.getItem(`nookify_like_${idx}`) === 'true') {
                heart.classList.add('liked');
            }
        });
    }

    _initViewer() {
        let container = document.getElementById('three-container') || document.querySelector('.modal-image-container');
        if (container && !this.viewer) {
            container.innerHTML = '';
            container.id = 'three-container';
            this.viewer = new SceneBuilder('three-container');
        }
    }

    async _createGeneratedCard(prompt, screenshotDataURL, customIndex = null, existingModelsData = null) {
        const cardsGrid = document.querySelector('.cards-grid');
        if (!cardsGrid) return null;

        const newIndex = customIndex !== null ? customIndex : document.querySelectorAll('.card').length;
        if (customIndex !== null && document.querySelector(`.card[data-card-index="${customIndex}"]`)) {
            return document.querySelector(`.card[data-card-index="${customIndex}"]`);
        }

        const newCard = document.createElement('div');
        newCard.className = 'card';
        newCard.setAttribute('data-card-index', newIndex);
        newCard.setAttribute('data-generated', 'true');

        const imageContent = screenshotDataURL
            ? `<img src="${screenshotDataURL}" alt="Scene" class="card-image" style="width:100%; height:100%; object-fit:cover;">`
            : `<div class="image-fallback" style="display:flex; background:linear-gradient(135deg, #667eea, #764ba2); color:white; padding:20px; text-align:center;">🎨 ${prompt.substring(0, 50)}...</div>`;

        newCard.innerHTML = `
            <div class="card-image-wrapper">
                ${imageContent}
                <div class="card-overlay"><p class="card-description">${prompt.replace(/'/g, "\\'")}</p></div>
                <div class="card-heart"><svg viewBox="0 0 24 24"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg></div>
            </div>`;

        cardsGrid.prepend(newCard);
        this._bindCardEvents(newCard, newIndex);

        if (existingModelsData && !savedScenes.has(newIndex)) {
            saveSceneToStorage(newIndex, { prompt, screenshot: screenshotDataURL, modelsData: existingModelsData });
        }
        return newCard;
    }

    _bindCardEvents(card, idx) {
        const heart = card.querySelector('.card-heart');
        if (heart) {
            const saved = localStorage.getItem(`nookify_like_${idx}`);
            if (saved === 'true') heart.classList.add('liked');
            heart.addEventListener('click', (e) => {
                e.stopPropagation();
                heart.classList.toggle('liked');
                localStorage.setItem(`nookify_like_${idx}`, heart.classList.contains('liked'));
                const activeTab = document.querySelector('.tab.active')?.textContent.trim();
                if (['Liked', 'Понравившиеся'].includes(activeTab)) this._filterCards(activeTab);
            });
        }
        card.addEventListener('click', () => this._openModal(card));
    }

    _bindUI() {
        const searchBtn = document.querySelector('.search-btn');
        const searchInput = document.getElementById('searchInput');
        if (searchBtn) searchBtn.addEventListener('click', () => this._generate());
        if (searchInput) searchInput.addEventListener('keypress', e => e.key === 'Enter' && this._generate());

        document.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                this._filterCards(tab.textContent.trim());
            });
        });
    }

    _bindExportSettings() {
        const allGroups = document.querySelectorAll('#resultModal .option-group');
        allGroups.forEach(group => {
            const label = group.closest('.export-section')?.querySelector('.export-label')?.textContent || '';
            const btns = group.querySelectorAll('.option-btn');
            btns.forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    btns.forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    const val = btn.textContent.trim();
                    if (label === 'Geometry') currentExportSettings.geometry = val;
                    if (label === 'Format') currentExportSettings.format = val.toLowerCase();
                    if (label === 'Material') currentExportSettings.material = val;
                    if (label === 'Resolution') currentExportSettings.resolution = val;
                    this._updateSliderPosition(group);
                });
            });
            setTimeout(() => this._updateSliderPosition(group), 100);
        });
    }

    _updateSliderPosition(group) {
        const slider = group.querySelector('.option-slider');
        const activeBtn = group.querySelector('.option-btn.active');
        if (!slider || !activeBtn) return;
        const gRect = group.getBoundingClientRect();
        const bRect = activeBtn.getBoundingClientRect();
        slider.style.transform = `translateX(${(bRect.left - gRect.left) + 4}px)`;
        slider.style.width = `${bRect.width - 8}px`;
    }

    _fixImageErrors() {
        document.querySelectorAll('.card-image').forEach(img => {
            img.addEventListener('error', function() {
                const fallback = this.parentElement?.querySelector('.image-fallback');
                if (fallback) { this.style.display = 'none'; fallback.style.display = 'flex'; }
            });
        });
    }

    async _generate() {
        const input = document.getElementById('searchInput');
        const prompt = input?.value.trim();
        if (!prompt) return;
        this.currentPrompt = prompt;
        const loading = document.getElementById('loading');
        if (loading) loading.classList.add('active');

        try {
            console.log(`📡 Отправка запроса на бэкенд: "${prompt}"`);
            const url = `${CONFIG.generateUrl}?query=${encodeURIComponent(prompt)}`;
            const response = await fetch(url, { method: 'POST' });
            if (!response.ok) throw new Error(`Ошибка HTTP ${response.status}`);
            const data = await response.json();
            const sceneItems = Array.isArray(data) ? data : (data.items || data.models || []);

            if (this.viewer) {
                this.viewer.clear();
                currentSceneModels = [];
                for (const item of sceneItems) {
                    await this.viewer.addModel(item);
                    currentSceneModels.push(item);
                }
            }

            await new Promise(r => setTimeout(r, 800));
            const screenshotDataURL = this.viewer ? await this.viewer.captureScreenshot() : null;
            const sceneModelsData = this.viewer ? this.viewer.getCurrentSceneData() : [];

            const newCard = await this._createGeneratedCard(prompt, screenshotDataURL);
            const newCardIndex = parseInt(newCard.getAttribute('data-card-index'));
            saveSceneToStorage(newCardIndex, { prompt, screenshot: screenshotDataURL, modelsData: sceneModelsData });

            this._openModalByPrompt(prompt, newCardIndex);
        } catch (err) {
            console.error('❌ Ошибка:', err);
            alert(`Ошибка: ${err.message}`);
        } finally {
            if (loading) loading.classList.remove('active');
        }
    }

    _filterCards(activeTab) {
        document.querySelectorAll('.card').forEach(card => {
            const isLiked = card.querySelector('.card-heart')?.classList.contains('liked');
            const isGen = card.getAttribute('data-generated') === 'true';
            let show = true;
            if (['Liked', 'Понравившиеся'].includes(activeTab)) show = isLiked;
            else if (['Mine', 'Мои'].includes(activeTab)) show = isGen;
            else if (['Trending', 'Тренды'].includes(activeTab)) show = !isGen;
            card.style.display = show ? 'block' : 'none';
        });
    }

    _openModal(card) {
        const desc = card.querySelector('.card-description')?.textContent || '';
        const idx = parseInt(card.dataset.cardIndex);
        const savedScene = savedScenes.get(idx);

        if (savedScene?.modelsData && this.viewer) {
            this.viewer.restoreScene(savedScene.modelsData, (models) => { currentSceneModels = models; });
        }
        this._openModalByPrompt(desc, idx);
    }

    _openModalByPrompt(prompt, cardIndex = null) {
        const promptEl = document.getElementById('promptText');
        if (promptEl) promptEl.textContent = prompt;
        const modal = document.getElementById('resultModal');

        if (modal && cardIndex !== null) {
            modal.dataset.currentCardIndex = cardIndex;
            const modalHeart = document.querySelector('.modal-heart');
            if (modalHeart) {
                if (localStorage.getItem(`nookify_like_${cardIndex}`) === 'true') modalHeart.classList.add('liked');
                else modalHeart.classList.remove('liked');
            }
            modal.classList.add('active');
            document.body.style.overflow = 'hidden';
            setTimeout(() => this.viewer?._onResize(), 150);
        }
    }

    exportModel() {
        const models = this.viewer?.getCurrentSceneData() || currentSceneModels;
        if (!models || models.length === 0) return alert('No models to export!');

        const exportData = {
            exportedAt: new Date().toISOString(),
            settings: currentExportSettings,
            models: models
        };

        const format = currentExportSettings.format;
        const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `nookify-scene-${Date.now()}.${format}`;
        a.click();
        URL.revokeObjectURL(a.href);
        alert(`✅ Scene exported as ${format.toUpperCase()}`);
    }
}

// ========================================
// INITIALIZATION AND EVENT BINDING (CLEANED)
// ========================================
const translations = {
    en: { searchPlaceholder: "Type your wishes" },
    ru: { searchPlaceholder: "Введи свои пожелания" }
};
let currentLang = localStorage.getItem('nookify_lang') || 'en';

function updateLanguage(lang) {
    currentLang = lang;
    localStorage.setItem('nookify_lang', lang);
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (translations[lang] && translations[lang][key]) el.innerHTML = translations[lang][key];
    });
    const searchInput = document.getElementById('searchInput');
    if (searchInput && translations[lang].searchPlaceholder) searchInput.placeholder = translations[lang].searchPlaceholder;
}

window.toggleLanguage = () => updateLanguage(currentLang === 'en' ? 'ru' : 'en');
window.regenerateDesign = () => window.app?._generate();
window.downloadDesign = () => window.app?.exportModel();
window.openAuthModal = () => { document.getElementById('authModal')?.style.setProperty('display', 'flex', 'important'); document.body.style.overflow = 'hidden'; };
window.closeAuthModal = () => { document.getElementById('authModal')?.style.setProperty('display', 'none', 'important'); document.body.style.overflow = ''; };
window.handleLogin = (e) => { e?.preventDefault(); alert(currentLang === 'en' ? '✅ Login successful!' : '✅ Вход выполнен!'); window.closeAuthModal(); return false; };

document.addEventListener('DOMContentLoaded', () => {
    // Инициализация ядра
    window.app = new NookifyApp();
    window.app.init();
    updateLanguage(currentLang);

    // Модалки
    document.querySelectorAll('.modal-close, .modal-overlay, .auth-close').forEach(el => {
        el.addEventListener('click', (e) => {
            if (e.target === el || el.classList.contains('modal-close') || el.classList.contains('auth-close')) {
                el.closest('.modal-container, .auth-modal')?.classList.remove('active');
                if(el.closest('#authModal')) window.closeAuthModal();
                document.body.style.overflow = '';
            }
        });
    });

    document.addEventListener('keydown', e => { if (e.key === 'Escape') {
        document.querySelectorAll('.modal-container, .auth-modal').forEach(m => m.classList.remove('active'));
        window.closeAuthModal();
        document.body.style.overflow = '';
    }});

    // Подмена кнопки логина
    const oldBtn = document.querySelector('.btn-login');
    if (oldBtn) {
        const newBtn = oldBtn.cloneNode(true);
        newBtn.onclick = (e) => { e.preventDefault(); window.openAuthModal(); };
        oldBtn.parentNode.replaceChild(newBtn, oldBtn);
    }
});