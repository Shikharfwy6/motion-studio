# Motion Studio

Mobile motion-graphics editor (canvas preview, layers, keyframes, easing, presets, video export).
Built with plain HTML/CSS/JS + Capacitor. The APK is built by GitHub Actions.

## APK kaise banaye
1. Is poore folder ko ek naye GitHub repo me push karo (branch: main).
2. Repo > Actions > "Build APK" workflow khud chalega (ya "Run workflow" dabao).
3. Run complete hone par page ke neeche Artifacts me `motion-studio-apk` download karo.
4. Zip kholo, `app-debug.apk` phone me install karo (Unknown sources allow karna padega).

## Local test
Open `www/index.html` in Chrome (mobile view). Export browser me download hoga.
