(function () {
    if (window.__betterWebTvInstalled) {
        if (typeof window.__betterWebTvScan === 'function') {
            window.__betterWebTvScan();
        }
        return;
    }
    window.__betterWebTvInstalled = true;
    window.__betterWebTvEnabled = true;
    var notifiedPlaying = false;
    var notifiedStall = false;
    var lastClickedPlayAt = 0;
    var lastCurrentTime = -1;
    var stallTicks = 0;
    var mutationTimer = null;

    var STYLE_ID = 'better-webtv-fs-style';
    var CLASS_ACTIVE = 'better-webtv-fs-active';
    var CLASS_ANCESTOR = 'better-webtv-fs-ancestor';
    var CLASS_VIDEO = 'better-webtv-fs-video';

    function removeClassFromAll(className) {
        var nodes = document.querySelectorAll('.' + className);
        for (var i = 0; i < nodes.length; i++) {
            nodes[i].classList.remove(className);
        }
    }

    function clearFullscreen() {
        var existing = document.getElementById(STYLE_ID);
        if (existing && existing.parentNode) {
            existing.parentNode.removeChild(existing);
        }
        if (document.documentElement) {
            document.documentElement.classList.remove(CLASS_ACTIVE);
        }
        if (document.body) {
            document.body.classList.remove(CLASS_ACTIVE);
        }
        removeClassFromAll(CLASS_ANCESTOR);
        removeClassFromAll(CLASS_VIDEO);
    }

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = [
            'html.' + CLASS_ACTIVE + ', body.' + CLASS_ACTIVE + ' {',
            '  margin: 0 !important;',
            '  padding: 0 !important;',
            '  overflow: hidden !important;',
            '  background: #000000 !important;',
            '  width: 100vw !important;',
            '  height: 100vh !important;',
            '}',
            'body.' + CLASS_ACTIVE + ' > *:not(.' + CLASS_ANCESTOR + '):not(.' + CLASS_VIDEO + '),',
            '.' + CLASS_ANCESTOR + ' > *:not(.' + CLASS_ANCESTOR + '):not(.' + CLASS_VIDEO + ') {',
            '  display: none !important;',
            '  visibility: hidden !important;',
            '  opacity: 0 !important;',
            '  pointer-events: none !important;',
            '}',
            '.' + CLASS_ANCESTOR + '::before,',
            '.' + CLASS_ANCESTOR + '::after,',
            'body.' + CLASS_ACTIVE + '::before,',
            'body.' + CLASS_ACTIVE + '::after {',
            '  display: none !important;',
            '  content: none !important;',
            '}',
            '.' + CLASS_ANCESTOR + ' {',
            '  position: static !important;',
            '  width: 100vw !important;',
            '  height: 100vh !important;',
            '  min-width: 100vw !important;',
            '  min-height: 100vh !important;',
            '  max-width: 100vw !important;',
            '  max-height: 100vh !important;',
            '  margin: 0 !important;',
            '  padding: 0 !important;',
            '  border: 0 !important;',
            '  transform: none !important;',
            '  filter: none !important;',
            '  backdrop-filter: none !important;',
            '  clip-path: none !important;',
            '  overflow: visible !important;',
            '  z-index: auto !important;',
            '  opacity: 1 !important;',
            '  visibility: visible !important;',
            '  background: #000000 !important;',
            '}',
            '.' + CLASS_VIDEO + ' {',
            '  position: fixed !important;',
            '  inset: 0 !important;',
            '  top: 0 !important;',
            '  left: 0 !important;',
            '  right: 0 !important;',
            '  bottom: 0 !important;',
            '  width: 100vw !important;',
            '  height: 100vh !important;',
            '  max-width: 100vw !important;',
            '  max-height: 100vh !important;',
            '  z-index: 2147483640 !important;',
            '  background: #000000 !important;',
            '  object-fit: contain !important;',
            '  transform: none !important;',
            '  visibility: visible !important;',
            '  opacity: 1 !important;',
            '}'
        ].join('\n');
        (document.head || document.documentElement).appendChild(style);
    }

    function collectShadowVideos(root, result) {
        if (!root || !root.querySelectorAll) return;
        var all = root.querySelectorAll('*');
        for (var j = 0; j < all.length; j++) {
            var sr = all[j].shadowRoot;
            if (sr) {
                var list = sr.querySelectorAll('video');
                for (var i = 0; i < list.length; i++) {
                    result.push(list[i]);
                }
                collectShadowVideos(sr, result);
            }
        }
    }

    function collectVideos() {
        var result = [];
        var mainList = document.querySelectorAll('video');
        for (var i = 0; i < mainList.length; i++) {
            result.push(mainList[i]);
        }
        if (result.length === 0) {
            collectShadowVideos(document, result);
        }
        return result;
    }

    function pickBestVideo(videos) {
        if (!videos.length) return null;
        var best = videos[0];
        var bestScore = -1;
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var rect = v.getBoundingClientRect ? v.getBoundingClientRect() : { width: 0, height: 0 };
            var area = (rect.width || v.videoWidth || 0) * (rect.height || v.videoHeight || 0);
            var readyBonus = (v.readyState >= 2 ? 10000000 : 0) + (!v.paused ? 5000000 : 0);
            var srcBonus = (v.currentSrc || v.src) ? 1000000 : 0;
            var score = readyBonus + srcBonus + area;
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    function tryClickPlayButton() {
        var now = Date.now();
        if (now - lastClickedPlayAt < 1800) return;
        var selectors = [
            '.vjs-big-play-button',
            'button[aria-label*="play" i]',
            'button[aria-label*="播放"]',
            'button[title*="播放"]',
            '.play-btn',
            '.btn-play'
        ];
        for (var i = 0; i < selectors.length; i++) {
            var btn = document.querySelector(selectors[i]);
            if (btn && btn.offsetParent !== null) {
                lastClickedPlayAt = now;
                try { btn.click(); } catch (e) {}
                return;
            }
        }
    }

    function notifyPlayingIfReady(video) {
        if (notifiedPlaying || !video) return;
        if (video.readyState >= 2 || (!video.paused && video.currentTime > 0)) {
            notifiedPlaying = true;
            if (window.BetterWebTvBridge && typeof window.BetterWebTvBridge.onVideoPlaying === 'function') {
                try { window.BetterWebTvBridge.onVideoPlaying(); } catch (e) {}
            }
        }
    }

    function reportStall(reason) {
        if (notifiedStall || !window.__betterWebTvEnabled) return;
        notifiedStall = true;
        if (window.BetterWebTvBridge && typeof window.BetterWebTvBridge.onPlaybackStalled === 'function') {
            try { window.BetterWebTvBridge.onPlaybackStalled(reason); } catch (e) {}
        }
    }

    function syncAncestors(target) {
        var chain = [];
        var node = target.parentElement || (target.getRootNode && target.getRootNode().host) || null;
        while (node && node !== document.body && node !== document.documentElement) {
            chain.push(node);
            node = node.parentElement || (node.getRootNode && node.getRootNode().host) || null;
        }

        var oldAncestors = document.querySelectorAll('.' + CLASS_ANCESTOR);
        for (var i = 0; i < oldAncestors.length; i++) {
            if (chain.indexOf(oldAncestors[i]) === -1) {
                oldAncestors[i].classList.remove(CLASS_ANCESTOR);
            }
        }
        for (var j = 0; j < chain.length; j++) {
            if (!chain[j].classList.contains(CLASS_ANCESTOR)) {
                chain[j].classList.add(CLASS_ANCESTOR);
            }
        }

        if (document.documentElement && !document.documentElement.classList.contains(CLASS_ACTIVE)) {
            document.documentElement.classList.add(CLASS_ACTIVE);
        }
        if (document.body && !document.body.classList.contains(CLASS_ACTIVE)) {
            document.body.classList.add(CLASS_ACTIVE);
        }
    }

    function applyFullscreen() {
        if (!window.__betterWebTvEnabled) {
            clearFullscreen();
            return;
        }

        var videos = collectVideos();
        var target = pickBestVideo(videos);
        if (!target) {
            clearFullscreen();
            tryClickPlayButton();
            return;
        }

        ensureStyle();
        syncAncestors(target);

        if (!target.classList.contains(CLASS_VIDEO)) {
            var oldVideos = document.querySelectorAll('.' + CLASS_VIDEO);
            for (var i = 0; i < oldVideos.length; i++) {
                if (oldVideos[i] !== target) {
                    oldVideos[i].classList.remove(CLASS_VIDEO);
                }
            }
            target.classList.add(CLASS_VIDEO);
        }

        if (target.getAttribute('playsinline') !== 'true') {
            target.setAttribute('playsinline', 'true');
        }
        if (target.getAttribute('autoplay') !== 'true') {
            target.setAttribute('autoplay', 'true');
        }

        if (!target.__betterWebTvBound) {
            target.__betterWebTvBound = true;
            target.addEventListener('playing', function () {
                stallTicks = 0;
                notifyPlayingIfReady(target);
            });
            target.addEventListener('canplay', function () {
                notifyPlayingIfReady(target);
            });
            target.addEventListener('error', function () {
                reportStall('video_element_error');
            });
        }

        if (target.paused) {
            tryClickPlayButton();
            var p = target.play();
            if (p && typeof p.catch === 'function') {
                p.catch(function () {
                    target.muted = true;
                    var p2 = target.play();
                    if (p2 && typeof p2.then === 'function') {
                        p2.then(function () {
                            setTimeout(function () {
                                try { target.muted = false; } catch (e) {}
                            }, 500);
                        }).catch(function () {});
                    }
                });
            }
        }
        notifyPlayingIfReady(target);
    }

    function checkPlaybackStall() {
        if (!window.__betterWebTvEnabled || notifiedStall) return;
        var target = document.querySelector('.' + CLASS_VIDEO);
        if (!target) return;
        if (target.error) {
            reportStall('video_error');
            return;
        }
        if (target.readyState >= 1 && !target.paused) {
            if (lastCurrentTime >= 0 && target.currentTime === lastCurrentTime) {
                stallTicks++;
                if (stallTicks >= 4) {
                    reportStall('video_stalled_10s');
                }
            } else {
                lastCurrentTime = target.currentTime;
                stallTicks = 0;
            }
        }
    }

    window.__betterWebTvSetEnabled = function (enabled) {
        window.__betterWebTvEnabled = !!enabled;
        if (enabled) {
            notifiedPlaying = false;
            notifiedStall = false;
            stallTicks = 0;
            lastCurrentTime = -1;
            applyFullscreen();
        } else {
            clearFullscreen();
        }
    };

    window.__betterWebTvScan = function () {
        notifiedPlaying = false;
        notifiedStall = false;
        stallTicks = 0;
        lastCurrentTime = -1;
        applyFullscreen();
    };

    if (window.MutationObserver && document.documentElement) {
        var observer = new MutationObserver(function () {
            if (!window.__betterWebTvEnabled || mutationTimer) return;
            mutationTimer = setTimeout(function () {
                mutationTimer = null;
                if (window.__betterWebTvEnabled) {
                    applyFullscreen();
                }
            }, 250);
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    applyFullscreen();
    setInterval(function () {
        if (window.__betterWebTvEnabled) {
            applyFullscreen();
        }
    }, 1200);
    setInterval(checkPlaybackStall, 2500);
})();
