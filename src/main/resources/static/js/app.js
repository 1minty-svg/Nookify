// ========================================
// NOOKIFY APP - FULLY WORKING
// ========================================
// В САМОМ ВЕРХУ app.js, перед всеми классами


const CONFIG = {
    // Если бэкенд запущен на 8080, а фронт на 63342 - нужен полный URL
    modelsBaseUrl: 'http://localhost:8080/api/models',
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

// Хранилище данных сцен
let savedScenes = new Map();

// Загрузка сохранённых сцен из localStorage
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

// Сохранение сцены в localStorage
function saveSceneToStorage(cardIndex, sceneData) {
    savedScenes.set(cardIndex, sceneData);
    localStorage.setItem('nookify_saved_scenes', JSON.stringify([...savedScenes]));
}

const MODEL_SCHEMA = {
    required: ['id', 'name', 'category', 'tags', 'dimensions'],
    validate: (model) => {
        const errors = [];
        MODEL_SCHEMA.required.forEach(field => {
            if (!model[field]) errors.push(`Missing: ${field}`);
        });
        if (model.tags && !Array.isArray(model.tags)) errors.push('tags must be array');
        if (model.dimensions && typeof model.dimensions !== 'object') errors.push('dimensions must be object');
        return errors.length === 0 ? { valid: true } : { valid: false, errors };
    }
};

class Model3D {
    constructor(data) {
        Object.assign(this, data);
    }

    toJSON() {
        return {
            id: this.id,
            metadata: { name: this.name, category: this.category },
            properties: { tags: this.tags, polyCount: this.polyCount },
            geometry: this.dimensions,
            source: { path: this.modelPath }
        };
    }

    generateModelData(settings) {
        const polyMultiplier = settings.geometry === 'High Poly' ? 2.5 : 1;
        return {
            id: this.id,
            name: this.name,
            format: settings.format,
            vertices: Math.floor(this.polyCount * polyMultiplier * 3),
            polygons: Math.floor(this.polyCount * polyMultiplier),
            materials: settings.material === 'PBR' ? ['baseColor', 'metallic', 'roughness', 'normal'] : ['diffuse'],
            textures: settings.resolution === '4K' ? '4096x4096' : '2048x2048',
            dimensions: this.dimensions,
            colors: this._getColorByCategory(this.category)
        };
    }

    _getColorByCategory(category) {
        const colors = {
            'seating': { main: '#3b82f6', secondary: '#60a5fa' },
            'surface': { main: '#f59e0b', secondary: '#fbbf24' },
            'lighting': { main: '#10b981', secondary: '#34d399' },
            'decoration': { main: '#8b5cf6', secondary: '#a78bfa' },
            'storage': { main: '#ec4899', secondary: '#f472b6' }
        };
        return colors[category] || { main: '#64748b', secondary: '#94a3b8' };
    }
}

class ModelDatabase {
    constructor(url) {
        this.url = url;
        this.models = [];
        this.loaded = false;
    }

    async load() {
        try {
            const res = await fetch(this.url);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            const modelsArray = data.models || data;
            if (!Array.isArray(modelsArray)) throw new Error('Invalid data format');
            this.models = modelsArray.map(m => new Model3D(m));
            this.loaded = true;
            console.log(`📦 Loaded ${this.models.length} models`);
        } catch (e) {
            console.warn('⚠️ Using mock data:', e.message);
            this.models = this._getMockModels();
            this.loaded = true;
        }
    }

    filterByPrompt(prompt) {
        const keywords = prompt.toLowerCase().split(/\s+/).filter(Boolean);
        if (keywords.length === 0) return this.models;
        return this.models.filter(m => {
            const searchable = `${m.name} ${m.category} ${m.tags.join(' ')}`.toLowerCase();
            return keywords.some(k => searchable.includes(k));
        });
    }

    getById(id) {
        return this.models.find(m => m.id === id);
    }

    _getMockModels() {
        return [
            new Model3D({ id: 'sofa_scand_01', name: 'Scandinavian Sofa', category: 'seating', tags: ['sofa', 'scandinavian', 'grey', 'modern'], dimensions: { width: 2.4, height: 0.8, depth: 1.2 }, modelPath: 'SM_Prop_Couch_02.glb', polyCount: 15420 }),
            new Model3D({ id: 'table_wood_02', name: 'Wooden Coffee Table', category: 'surface', tags: ['table', 'wood', 'natural', 'round'], dimensions: { width: 1.0, height: 0.45, depth: 1.0 }, modelPath: 'Table_01.glb', polyCount: 8200 }),
            new Model3D({ id: 'lamp_arc_03', name: 'Arc Floor Lamp', category: 'lighting', tags: ['lamp', 'modern', 'brass', 'light'], dimensions: { width: 0.3, height: 1.8, depth: 0.3 }, modelPath: 'Fridge_01.glb', polyCount: 4500 }),
            new Model3D({ id: 'chair_eames_04', name: 'Eames Lounge Chair', category: 'seating', tags: ['chair', 'leather', 'classic', 'brown'], dimensions: { width: 0.84, height: 0.82, depth: 0.84 }, modelPath: 'Sink_01.glb', polyCount: 12300 })
        ];
    }
}

class SceneBuilder {
    constructor(containerId) {
        this.container = document.getElementById(containerId);
        if (!this.container) {
            console.error(`Container #${containerId} not found`);
            return;
        }

        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0xf8fafc);

        this.camera = new THREE.PerspectiveCamera(45, this.container.clientWidth / this.container.clientHeight, 0.1, 100);
        this.camera.position.set(4, 3, 5);
        this.camera.lookAt(0, 0.5, 0);

        this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
        this.renderer.setSize(this.container.clientWidth, this.container.clientHeight);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.renderer.shadowMap.enabled = true;
        this.container.appendChild(this.renderer.domElement);

        this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.05;
        this.controls.target.set(0, 0.5, 0);

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

        const planeMat = new THREE.MeshStandardMaterial({ color: 0xf1f5f9, roughness: 0.7, metalness: 0.05 });
        const plane = new THREE.Mesh(new THREE.PlaneGeometry(8, 8), planeMat);
        plane.rotation.x = -Math.PI / 2;
        plane.position.y = -0.02;
        plane.receiveShadow = true;
        plane.userData = { isFloor: true };
        this.scene.add(plane);
    }

    clear() {
        if (!this.scene) return;
        const toRemove = [];
        this.scene.traverse(obj => {
            if (obj.isMesh && !obj.userData?.isFloor && !obj.userData?.isGrid) {
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

async addModel(modelData) {
    if (!modelData.dimensions) return null;

    // Используем modelPath из models.json
    const modelFileName = modelData.modelPath;
    if (!modelFileName) {
        console.warn(`⚠️ Нет modelPath для ${modelData.id}`);
        return this._createFallbackBox(modelData);
    }

    const modelUrl = `${CONFIG.modelsBaseUrl}/${modelFileName}`;
    console.log(`📥 Загрузка: ${modelUrl}`);

    try {
        const loader = new THREE.GLTFLoader();

        const gltf = await new Promise((resolve, reject) => {
            loader.load(modelUrl, resolve, undefined, reject);
        });

        const mesh = gltf.scene;

        // Скейлинг под размеры
        const box = new THREE.Box3().setFromObject(mesh);
        const size = new THREE.Vector3();
        box.getSize(size);

        const targetW = modelData.dimensions.width || 1;
        const targetH = modelData.dimensions.height || 1;
        const targetD = modelData.dimensions.depth || 1;

        const scaleX = targetW / (size.x || 1);
        const scaleY = targetH / (size.y || 1);
        const scaleZ = targetD / (size.z || 1);
        const scale = Math.min(scaleX, scaleY, scaleZ);

        mesh.scale.setScalar(scale || 1);

        // Позиция
        mesh.position.set(
            (Math.random() - 0.5) * 3,
            (size.y * scale) / 2,
            (Math.random() - 0.5) * 2.5
        );

        mesh.traverse(child => {
            if (child.isMesh) {
                child.castShadow = true;
                child.receiveShadow = true;
            }
        });

        mesh.userData = {
            modelId: modelData.id,
            modelName: modelData.name,
            category: modelData.category
        };

        this.scene.add(mesh);
        console.log(`✅ Загружено: ${modelData.name}`);
        return mesh;
    } catch (err) {
        console.warn(`⚠️ Ошибка загрузки ${modelData.name}: ${err.message}`);
        return this._createFallbackBox(modelData);
    }
}

    _createFallbackBox(modelData) {
        const w = modelData.dimensions?.width || 0.8;
        const h = modelData.dimensions?.height || 0.8;
        const d = modelData.dimensions?.depth || 0.8;
        const geo = new THREE.BoxGeometry(w, h, d);
        const mat = new THREE.MeshStandardMaterial({ color: 0x94a3b8, roughness: 0.6 });
        const box = new THREE.Mesh(geo, mat);
        box.castShadow = true;
        box.receiveShadow = true;
        box.position.set((Math.random()-0.5)*3, h/2, (Math.random()-0.5)*2.5);
        box.userData = { modelId: modelData.id, modelName: modelData.name || 'Fallback', category: modelData.category };
        this.scene.add(box);
        return box;
    }

    async restoreScene(modelsData, updateCallback = null) {
        this.clear();
        const restoredModels = [];
        for (const modelData of modelsData) {
            const originalModel = window.app?.db?.getById(modelData.id);
            if (originalModel) {
                await this.addModel(originalModel);
                restoredModels.push(originalModel);
            }
        }
        if (updateCallback) updateCallback(restoredModels);
        return restoredModels;
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
            if (obj.isMesh && obj.userData?.modelId && !obj.userData?.isFloor && !obj.userData?.isGrid) {
                models.push({
                    id: obj.userData.modelId,
                    name: obj.userData.modelName,
                    category: obj.userData.category,
                    dimensions: { width: obj.geometry.parameters.width, height: obj.geometry.parameters.height, depth: obj.geometry.parameters.depth },
                    position: { x: obj.position.x, y: obj.position.y, z: obj.position.z }
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
                const dataURL = canvas.toDataURL('image/png');
                resolve(dataURL);
            }, 100);
        });
    }
}

class NookifyApp {
    constructor() {
        this.db = new ModelDatabase('/models.json');
        this.viewer = null;
        this.currentPrompt = '';
        this.currentModelsInScene = [];
        this.init();
    }

    async init() {
        loadSavedScenes();
        await this.db.load();
        this._initViewer();
        this._bindUI();
        this._bindExportSettings();
        this._renderModelsTable();
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
            if (!card.hasAttribute('data-card-index')) {
                card.setAttribute('data-card-index', idx);
            }
            const heart = card.querySelector('.card-heart');
            if (!heart) return;
            const saved = localStorage.getItem(`nookify_like_${idx}`);
            if (saved === 'true') {
                heart.classList.add('liked');
            } else {
                heart.classList.remove('liked');
            }
        });
    }

    _initViewer() {
        let container = document.getElementById('three-container');
        if (!container) {
            container = document.querySelector('.modal-image-container');
        }
        if (container && !this.viewer) {
            container.innerHTML = '';
            container.id = 'three-container';
            this.viewer = new SceneBuilder('three-container');
        }
    }

    async _createGeneratedCard(prompt, screenshotDataURL, customIndex = null, existingModelsData = null) {
        const cardsGrid = document.querySelector('.cards-grid');
        if (!cardsGrid) return null;

        let newIndex;
        let newCard;

        if (customIndex !== null) {
            const existingCard = document.querySelector(`.card[data-card-index="${customIndex}"]`);
            if (existingCard) {
                return existingCard;
            }
            newIndex = customIndex;
            newCard = document.createElement('div');
        } else {
            const existingCards = document.querySelectorAll('.card');
            newIndex = existingCards.length;
            newCard = document.createElement('div');
        }

        newCard.className = 'card';
        newCard.setAttribute('data-card-index', newIndex);
        newCard.setAttribute('data-generated', 'true');

        let imageContent;
        if (screenshotDataURL) {
            imageContent = `<img src="${screenshotDataURL}" alt="Generated Scene" class="card-image" style="width:100%; height:100%; object-fit:cover;">`;
        } else {
            imageContent = `<div class="image-fallback" style="display: flex; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; font-size: 14px; align-items: center; justify-content: center; text-align: center; padding: 20px;">
                🎨 ${prompt.substring(0, 80)}${prompt.length > 80 ? '...' : ''}
            </div>`;
        }

        newCard.innerHTML = `
            <div class="card-image-wrapper">
                ${imageContent}
                <div class="card-overlay">
                    <p class="card-description">${prompt.replace(/'/g, "\\'")}</p>
                </div>
                <div class="card-heart">
                    <svg viewBox="0 0 24 24">
                        <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
                    </svg>
                </div>
            </div>
        `;

        cardsGrid.appendChild(newCard);
        this._bindCardEvents(newCard, newIndex);

        if (existingModelsData && !savedScenes.has(newIndex)) {
            saveSceneToStorage(newIndex, {
                prompt: prompt,
                screenshot: screenshotDataURL,
                modelsData: existingModelsData
            });
        }

        return newCard;
    }

    _bindCardEvents(card, idx) {
        const heart = card.querySelector('.card-heart');
        if (!heart) return;

        card.setAttribute('data-card-index', idx);

        const saved = localStorage.getItem(`nookify_like_${idx}`);
        if (saved === 'true') {
            heart.classList.add('liked');
        }

        const newHeart = heart.cloneNode(true);
        heart.parentNode.replaceChild(newHeart, heart);

        newHeart.addEventListener('click', (e) => {
            e.stopPropagation();
            newHeart.classList.toggle('liked');
            const isLiked = newHeart.classList.contains('liked');
            localStorage.setItem(`nookify_like_${idx}`, isLiked);

            const activeTab = document.querySelector('.tab.active')?.textContent.trim();
            if (activeTab === 'Liked' || activeTab === 'Понравившиеся') {
                this._filterCards(activeTab);
            }
            console.log(`❤️ Card ${idx} liked: ${isLiked}`);
        });

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

        document.querySelectorAll('.card').forEach((card, idx) => {
            this._bindCardEvents(card, idx);
        });

        const modalHeart = document.querySelector('.modal-heart');
        if (modalHeart) {
            const newModalHeart = modalHeart.cloneNode(true);
            modalHeart.parentNode.replaceChild(newModalHeart, modalHeart);

            newModalHeart.addEventListener('click', (e) => {
                e.stopPropagation();
                newModalHeart.classList.toggle('liked');
                const isLiked = newModalHeart.classList.contains('liked');
                const modal = document.getElementById('resultModal');
                const cardIndex = parseInt(modal?.dataset.currentCardIndex);

                console.log(`❤️ Modal like: ${isLiked}, cardIndex: ${cardIndex}`);

                if (cardIndex !== null && !isNaN(cardIndex) && cardIndex >= 0) {
                    localStorage.setItem(`nookify_like_${cardIndex}`, isLiked);

                    const card = document.querySelector(`.card[data-card-index="${cardIndex}"]`);
                    if (card) {
                        const heart = card.querySelector('.card-heart');
                        if (heart) {
                            if (isLiked) {
                                heart.classList.add('liked');
                            } else {
                                heart.classList.remove('liked');
                            }
                        }
                    }

                    const activeTab = document.querySelector('.tab.active')?.textContent.trim();
                    if (activeTab === 'Liked' || activeTab === 'Понравившиеся') {
                        this._filterCards(activeTab);
                    }
                }
            });
        }

        document.addEventListener('click', e => {
            const modal = document.getElementById('resultModal');
            if (e.target === modal) this._closeModal();
        });
        document.addEventListener('keydown', e => { if (e.key === 'Escape') this._closeModal(); });
    }

    _bindExportSettings() {
        const allGroups = document.querySelectorAll('#resultModal .option-group');
        let geometryGroup = null;
        let formatGroup = null;
        let materialGroups = [];
        let groupIndex = 0;

        allGroups.forEach(group => {
            const exportSection = group.closest('.export-section');
            const sectionLabel = exportSection?.querySelector('.export-label')?.textContent || '';
            if (sectionLabel === 'Geometry') {
                if (groupIndex === 0) geometryGroup = group;
                else if (groupIndex === 1) formatGroup = group;
            } else if (sectionLabel === 'Material') {
                materialGroups.push(group);
            }
            groupIndex++;
        });

        if (!geometryGroup) {
            geometryGroup = Array.from(allGroups).find(g => g.querySelector('.option-btn')?.textContent.includes('Low Poly'));
        }
        if (!formatGroup) {
            formatGroup = Array.from(allGroups).find(g => g.querySelector('.option-btn')?.textContent.includes('fbx'));
        }

        if (geometryGroup) {
            const activeBtn = geometryGroup.querySelector('.option-btn.active');
            if (activeBtn) currentExportSettings.geometry = activeBtn.textContent.trim();
            this._bindGroupButtons(geometryGroup, 'Geometry');
        }
        if (formatGroup) {
            const activeBtn = formatGroup.querySelector('.option-btn.active');
            if (activeBtn) currentExportSettings.format = activeBtn.textContent.trim().toLowerCase();
            this._bindGroupButtons(formatGroup, 'Format');
        }
        if (materialGroups[0]) {
            const activeBtn = materialGroups[0].querySelector('.option-btn.active');
            if (activeBtn) currentExportSettings.material = activeBtn.textContent.trim();
            this._bindGroupButtons(materialGroups[0], 'Material');
        }
        if (materialGroups[1]) {
            const activeBtn = materialGroups[1].querySelector('.option-btn.active');
            if (activeBtn) currentExportSettings.resolution = activeBtn.textContent.trim();
            this._bindGroupButtons(materialGroups[1], 'Resolution');
        }
        console.log('🎯 Initial export settings:', {...currentExportSettings});
    }

    _bindGroupButtons(group, settingName) {
        const btns = group.querySelectorAll('.option-btn');
        btns.forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                btns.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                const value = btn.textContent.trim();
                switch(settingName) {
                    case 'Geometry': currentExportSettings.geometry = value; break;
                    case 'Format': currentExportSettings.format = value.toLowerCase(); break;
                    case 'Material': currentExportSettings.material = value; break;
                    case 'Resolution': currentExportSettings.resolution = value; break;
                }
                console.log('✅ Export settings:', {...currentExportSettings});
                this._updateSliderPosition(group);
            });
        });
        setTimeout(() => this._updateSliderPosition(group), 10);
    }

    _updateSliderPosition(group) {
        const slider = group.querySelector('.option-slider');
        const activeBtn = group.querySelector('.option-btn.active');
        if (!slider || !activeBtn) return;
        const gRect = group.getBoundingClientRect();
        const bRect = activeBtn.getBoundingClientRect();
        const gap = 8;
        const w = bRect.width - gap;
        const l = (bRect.left - gRect.left) + gap / 2;
        slider.style.transform = `translateX(${l}px)`;
        slider.style.width = `${w}px`;
    }

    _fixImageErrors() {
        document.querySelectorAll('.card-image').forEach(img => {
            img.addEventListener('error', function() {
                const fallback = this.parentElement?.querySelector('.image-fallback');
                if (fallback) {
                    this.style.display = 'none';
                    fallback.style.display = 'flex';
                }
            });
        });
        const avatarImg = document.querySelector('.user-avatar img');
        if (avatarImg) {
            avatarImg.addEventListener('error', function() {
                this.style.display = 'none';
                const placeholder = this.nextElementSibling;
                if (placeholder) placeholder.style.display = 'flex';
            });
        }
    }

    async _generate() {
        const input = document.getElementById('searchInput');
        const prompt = input?.value.trim();
        if (!prompt) return;
        this.currentPrompt = prompt;
        const loading = document.getElementById('loading');
        if (loading) loading.classList.add('active');
        try {
            let results = this.db.filterByPrompt(prompt);
            if (results.length === 0) results = this.db.models.slice(0, 3);
            results = results.slice(0, 4);

            if (this.viewer) {
                this.viewer.clear();
                this.currentModelsInScene = [];
                for (const model of results) {
                    const validation = MODEL_SCHEMA.validate(model);
                    if (validation.valid) {
                        await this.viewer.addModel(model);
                        this.currentModelsInScene.push(model);
                    }
                }
            }

            await new Promise(resolve => setTimeout(resolve, 500));

            let screenshotDataURL = null;
            if (this.viewer) {
                screenshotDataURL = await this.viewer.captureScreenshot();
            }

            const sceneModelsData = this.viewer.getCurrentSceneData();

            const newCard = await this._createGeneratedCard(prompt, screenshotDataURL);
            const newCardIndex = newCard ? parseInt(newCard.getAttribute('data-card-index')) : -1;

            if (newCardIndex >= 0) {
                saveSceneToStorage(newCardIndex, {
                    prompt: prompt,
                    screenshot: screenshotDataURL,
                    modelsData: sceneModelsData
                });
            }

            this._openModalByPrompt(prompt, newCardIndex);
        } catch (err) {
            console.error('Generation failed:', err);
        } finally {
            if (loading) loading.classList.remove('active');
        }
    }

    _filterCards(activeTab) {
        console.log(`🔍 Filtering cards for tab: ${activeTab}`);
        const cards = document.querySelectorAll('.card');
        let visibleCount = 0;

        cards.forEach(card => {
            const heart = card.querySelector('.card-heart');
            const isLiked = heart?.classList.contains('liked') || false;
            const isGenerated = card.getAttribute('data-generated') === 'true';
            let show = true;

            if (activeTab === 'Liked' || activeTab === 'Понравившиеся') {
                show = isLiked;
            } else if (activeTab === 'Mine' || activeTab === 'Мои') {
                show = isGenerated;
            } else if (activeTab === 'Trending' || activeTab === 'Тренды') {
                show = !isGenerated;
            } else {
                show = true;
            }

            card.style.display = show ? 'block' : 'none';
            if (show) visibleCount++;
        });

        console.log(`📊 Visible cards: ${visibleCount}/${cards.length}`);
    }

    _openModal(card) {
        const desc = card.querySelector('.card-description')?.textContent || 'Custom interior design';
        const cards = document.querySelectorAll('.card');
        const cardIndex = Array.from(cards).indexOf(card);
        card.dataset.cardIndex = cardIndex;

        const savedScene = savedScenes.get(cardIndex);
        if (savedScene && savedScene.modelsData && this.viewer) {
            this.viewer.restoreScene(savedScene.modelsData, (restoredModels) => {
                this.currentModelsInScene = restoredModels;
            });
        }

        this._openModalByPrompt(desc, cardIndex);
    }

    _openModalByPrompt(prompt, cardIndex = null) {
        const promptEl = document.getElementById('promptText');
        if (promptEl) promptEl.textContent = prompt;

        const modal = document.getElementById('resultModal');
        if (cardIndex !== null && cardIndex >= 0) {
            modal.dataset.currentCardIndex = cardIndex;
            const modalHeart = document.querySelector('.modal-heart');
            if (modalHeart) {
                const isLiked = localStorage.getItem(`nookify_like_${cardIndex}`) === 'true';
                if (isLiked) {
                    modalHeart.classList.add('liked');
                } else {
                    modalHeart.classList.remove('liked');
                }
            }
        }

        if (modal) {
            modal.classList.add('active');
            document.body.style.overflow = 'hidden';
            setTimeout(() => this.viewer?._onResize(), 150);
        }
    }

    _closeModal() {
        const modal = document.getElementById('resultModal');
        if (modal) {
            modal.classList.remove('active');
            document.body.style.overflow = '';
        }
    }

    exportModel() {
        let exportModels = [];

        if (this.viewer && this.viewer.scene) {
            this.viewer.scene.traverse(obj => {
                if (obj.isMesh && obj.userData?.modelId && !obj.userData?.isFloor && !obj.userData?.isGrid) {
                    const originalModel = this.db.getById(obj.userData.modelId);
                    if (originalModel) {
                        exportModels.push(originalModel);
                    }
                }
            });
        }

        if (exportModels.length === 0 && this.currentModelsInScene.length > 0) {
            exportModels = this.currentModelsInScene;
        }

        if (exportModels.length === 0) {
            alert('No models in scene. Generate something first!');
            return;
        }

        const format = currentExportSettings.format;
        const geometry = currentExportSettings.geometry;
        const material = currentExportSettings.material;
        const resolution = currentExportSettings.resolution;

        console.log(`📦 Exporting: format=${format}, models: ${exportModels.length}`);

        const exportData = {
            exportedAt: new Date().toISOString(),
            settings: { geometry, format, material, resolution },
            models: exportModels.map(model => model.generateModelData(currentExportSettings)),
            sceneLayout: []
        };

        let blob, filename;
        if (format === 'fbx') {
            blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/octet-stream' });
            filename = `nookify-export-${Date.now()}.fbx`;
        } else if (format === 'obj') {
            blob = new Blob([this._generateOBJ(exportData)], { type: 'text/plain' });
            filename = `nookify-export-${Date.now()}.obj`;
        } else if (format === 'stl') {
            blob = new Blob([this._generateSTL(exportData)], { type: 'text/plain' });
            filename = `nookify-export-${Date.now()}.stl`;
        } else {
            blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
            filename = `nookify-export-${Date.now()}.json`;
        }

        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        alert(`✅ Exported as ${format.toUpperCase()}`);
    }

    _generateOBJ(exportData) {
        let objContent = `# Nookify Export\n`;
        objContent += `# Generated: ${exportData.exportedAt}\n`;
        objContent += `# Format: ${exportData.settings.format}\n\n`;

        exportData.models.forEach((model, idx) => {
            objContent += `o Model_${idx}_${model.id}\n`;
            const w = model.dimensions.width / 2;
            const h = model.dimensions.height / 2;
            const d = model.dimensions.depth / 2;
            const vertices = [[-w,-h,-d],[w,-h,-d],[w,-h,d],[-w,-h,d],[-w,h,-d],[w,h,-d],[w,h,d],[-w,h,d]];
            vertices.forEach(v => objContent += `v ${v[0]} ${v[1]} ${v[2]}\n`);
            const faces = [[0,1,2],[0,2,3],[7,6,5],[7,5,4],[4,5,1],[4,1,0],[5,6,2],[5,2,1],[6,7,3],[6,3,2],[7,4,0],[7,0,3]];
            faces.forEach(f => objContent += `f ${f[0]+1} ${f[1]+1} ${f[2]+1}\n`);
            objContent += `\n`;
        });
        return objContent;
    }

    _generateSTL(exportData) {
        let stlContent = `solid nookify_export\n`;
        stlContent += `  Generated: ${exportData.exportedAt}\n`;

        exportData.models.forEach((model, idx) => {
            stlContent += `\n  # Model: ${model.id}\n`;
            const w = model.dimensions.width / 2;
            const h = model.dimensions.height / 2;
            const d = model.dimensions.depth / 2;
            const vertices = [[-w,-h,-d],[w,-h,-d],[w,-h,d],[-w,-h,d],[-w,h,-d],[w,h,-d],[w,h,d],[-w,h,d]];
            const faces = [[0,1,2],[0,2,3],[7,6,5],[7,5,4],[4,5,1],[4,1,0],[5,6,2],[5,2,1],[6,7,3],[6,3,2],[7,4,0],[7,0,3]];
            faces.forEach(face => {
                const v = face.map(i => vertices[i]);
                stlContent += `  facet normal 0 0 0\n    outer loop\n`;
                v.forEach(vtx => stlContent += `      vertex ${vtx[0]} ${vtx[1]} ${vtx[2]}\n`);
                stlContent += `    endloop\n  endfacet\n`;
            });
        });
        stlContent += `endsolid nookify_export`;
        return stlContent;
    }

    _renderModelsTable() {
        const tbody = document.getElementById('modelsTableBody');
        if (!tbody || !this.db.loaded) return;
        tbody.innerHTML = this.db.models.map(m => `
            <tr onclick="window.app?._openModelInSearch('${m.tags?.[0] || m.category}')">
                <td><code>${m.id}</code></td>
                <td><strong>${m.name}</strong></td>
                <td><span class="badge">${m.category}</span></td>
                <td>${(m.tags || []).map(t => `<span class="tag">${t}</span>`).join(' ')}</td>
                <td>${m.dimensions?.width || '?'}×${m.dimensions?.height || '?'}×${m.dimensions?.depth || '?'}m</td>
                <td><button class="btn-sm" onclick="event.stopPropagation(); alert('${JSON.stringify(m.toJSON(), null, 2).replace(/'/g, "\\'")}')">View JSON</button></td>
            </tr>
        `).join('');
    }

    _openModelInSearch(tag) {
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            searchInput.value = tag;
            this._generate();
        }
    }
}

let appInstance = null;
document.addEventListener('DOMContentLoaded', () => {
    appInstance = new NookifyApp();
    window.app = appInstance;
});

const translations = {
    en: { tagline: "AI Interior Generator<br>Bring Your Dreams to Life in Seconds", searchPlaceholder: "Type your wishes", exploreTitle: "Explore", tabTrending: "Trending", tabMine: "Mine", tabLiked: "Liked", loginTitle: "Welcome Back", loginEmail: "Email", loginPass: "Password", loginBtn: "Log In", registerLink: "Don't have an account? <span style='color:#0066cc; cursor:pointer; font-weight:600;'>Sign up</span>" },
    ru: { tagline: "AI Генератор Интерьеров<br>Воплоти свои мечты за секунды", searchPlaceholder: "Введи свои пожелания", exploreTitle: "Исследовать", tabTrending: "Тренды", tabMine: "Мои", tabLiked: "Понравившиеся", loginTitle: "С возвращением", loginEmail: "Почта", loginPass: "Пароль", loginBtn: "Войти", registerLink: "Нет аккаунта? <span style='color:#0066cc; cursor:pointer; font-weight:600;'>Зарегистрироваться</span>" }
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
    const langSwitch = document.getElementById('langSwitch');
    if (langSwitch) langSwitch.textContent = lang === 'en' ? 'Ru' : 'En';
}

function toggleLanguage() { updateLanguage(currentLang === 'en' ? 'ru' : 'en'); }
function openAuthModal() { document.getElementById('authModal')?.classList.add('active'); document.body.style.overflow = 'hidden'; }
function closeAuthModal() { document.getElementById('authModal')?.classList.remove('active'); document.body.style.overflow = ''; }
function handleLogin(e) { if (e) e.preventDefault(); alert(currentLang === 'en' ? '✅ Login successful! (Demo)' : '✅ Вход выполнен! (Демо)'); closeAuthModal(); }
function regenerateDesign() { window.app?._generate(); }
function downloadDesign() { window.app?.exportModel(); }

window.openAuthModal = openAuthModal;
window.closeAuthModal = closeAuthModal;
window.handleLogin = handleLogin;
window.toggleLanguage = toggleLanguage;
window.regenerateDesign = regenerateDesign;
window.downloadDesign = downloadDesign;

document.addEventListener('DOMContentLoaded', () => {
    updateLanguage(currentLang);
    document.addEventListener('click', (e) => { if (e.target.id === 'authModal') closeAuthModal(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeAuthModal(); });
});

function initSmoothOptionSlider() {
    document.querySelectorAll('.option-group').forEach(group => {
        const slider = group.querySelector('.option-slider');
        const btns = group.querySelectorAll('.option-btn');
        if (!slider || !btns.length) return;
        const update = () => {
            const active = group.querySelector('.option-btn.active');
            if (!active) return;
            const gRect = group.getBoundingClientRect();
            const bRect = active.getBoundingClientRect();
            const gap = 8;
            slider.style.transform = `translateX(${(bRect.left - gRect.left) + gap/2}px)`;
            slider.style.width = `${bRect.width - gap}px`;
        };
        btns.forEach(btn => btn.addEventListener('click', () => { btns.forEach(x => x.classList.remove('active')); btn.classList.add('active'); update(); }));
        update();
        window.addEventListener('resize', update);
    });
}
document.addEventListener('DOMContentLoaded', initSmoothOptionSlider);

// ========================================
// FIX LOGIN BUTTON - 100% WORKING
// ========================================

(function fixLoginButton() {
    // Ждём загрузки страницы
    function init() {
        const oldBtn = document.querySelector('.btn-login');
        if (!oldBtn) {
            console.log('Login button not found yet, waiting...');
            return;
        }

        // Создаём новую кнопку
        const newBtn = document.createElement('button');
        newBtn.className = 'btn-login';
        newBtn.textContent = 'Log In';

        // Копируем стили со старой кнопки
        const computed = window.getComputedStyle(oldBtn);
        newBtn.style.padding = computed.padding;
        newBtn.style.background = computed.background;
        newBtn.style.border = computed.border;
        newBtn.style.borderRadius = computed.borderRadius;
        newBtn.style.fontSize = computed.fontSize;
        newBtn.style.fontWeight = computed.fontWeight;
        newBtn.style.color = computed.color;
        newBtn.style.cursor = 'pointer';
        newBtn.style.position = 'relative';
        newBtn.style.zIndex = '10000';

        // Добавляем обработчик
        newBtn.onclick = function(e) {
            e.preventDefault();
            e.stopPropagation();
            const modal = document.getElementById('authModal');
            if (modal) {
                modal.style.display = 'flex';
                document.body.style.overflow = 'hidden';
                console.log('Login modal opened');
            } else {
                console.error('Auth modal not found');
            }
            return false;
        };

        // Заменяем старую кнопку
        oldBtn.parentNode.replaceChild(newBtn, oldBtn);
        console.log('✅ Login button fixed and working!');
    }

    // Запускаем когда страница загрузится
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();

// ========================================
// CLOSE MODAL FUNCTIONS
// ========================================

// Закрытие по крестику
window.closeAuthModal = function() {
    const modal = document.getElementById('authModal');
    if (modal) {
        modal.style.display = 'none';
        document.body.style.overflow = '';
        console.log('Modal closed');
    }
};

// Закрытие по клику вне модалки
document.addEventListener('click', function(e) {
    const modal = document.getElementById('authModal');
    if (modal && modal.style.display === 'flex') {
        // Если кликнули на фон (сам modal)
        if (e.target === modal) {
            modal.style.display = 'none';
            document.body.style.overflow = '';
        }
    }
});

// Закрытие по ESC
document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
        const modal = document.getElementById('authModal');
        if (modal && modal.style.display === 'flex') {
            modal.style.display = 'none';
            document.body.style.overflow = '';
        }
    }
});

// Обработчик формы логина
window.handleLogin = function(e) {
    if (e) e.preventDefault();
    const lang = localStorage.getItem('nookify_lang') || 'en';
    alert(lang === 'en' ? '✅ Login successful! (Demo)' : '✅ Вход выполнен! (Демо)');
    closeAuthModal();
    return false;
};

// Привязываем закрытие к кнопке с крестиком
document.addEventListener('DOMContentLoaded', function() {
    const closeBtn = document.querySelector('.auth-close');
    if (closeBtn) {
        closeBtn.onclick = function(e) {
            e.preventDefault();
            closeAuthModal();
        };
    }

    // Привязываем форму логина
    const authForm = document.querySelector('.auth-form');
    if (authForm) {
        authForm.onsubmit = handleLogin;
    }
});

// ========================================
// FIX: SIGN UP TOGGLE IN AUTH MODAL
// ========================================
document.addEventListener('click', (e) => {
    // Ловим клик по span внутри футера авторизации
    if (e.target.closest('.auth-footer span')) {
        const modal = document.getElementById('authModal');
        const title = modal.querySelector('.auth-title');
        const btn = modal.querySelector('.btn-auth');
        const footer = modal.querySelector('.auth-footer');

        // Определяем текущий режим по заголовку
        const isLoginMode = title.textContent.toLowerCase().includes('welcome') ||
                            title.textContent.toLowerCase().includes('возвращением');

        if (isLoginMode) {
            // 🔽 ПЕРЕКЛЮЧЕНИЕ НА РЕГИСТРАЦИЮ
            title.textContent = currentLang === 'en' ? 'Create Account' : 'Создать аккаунт';
            btn.textContent = currentLang === 'en' ? 'Sign Up' : 'Зарегистрироваться';
            footer.innerHTML = currentLang === 'en'
                ? 'Already have an account? <span style="color:#0066cc; cursor:pointer; font-weight:600;">Log In</span>'
                : 'Уже есть аккаунт? <span style="color:#0066cc; cursor:pointer; font-weight:600;">Войти</span>';
            // Опционально: покажем демо-алерт
            // alert(currentLang === 'en' ? '📝 Switched to Sign Up mode!' : '📝 Режим регистрации активирован!');
        } else {
            // 🔼 ВОЗВРАТ НА ВХОД
            title.textContent = currentLang === 'en' ? 'Welcome Back' : 'С возвращением';
            btn.textContent = currentLang === 'en' ? 'Log In' : 'Войти';
            footer.innerHTML = currentLang === 'en'
                ? 'Don\'t have an account? <span style="color:#0066cc; cursor:pointer; font-weight:600;">Sign up</span>'
                : 'Нет аккаунта? <span style="color:#0066cc; cursor:pointer; font-weight:600;">Зарегистрироваться</span>';
        }
    }
});