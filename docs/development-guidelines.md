# Правила разработки VoiceKB

Как писать, собирать, проверять и выпускать код в этом проекте. Здесь нет описания того, почему сделаны конкретные фичи (это в `docs/development/`), и нет описания пользовательского поведения (это в `docs/functional-spec/`).

## Стек

- Язык: Java (уровень исходников и байт-кода — Java 8).
- UI: Android XML-разметки и drawable.
- Компоненты UI: AndroidX AppCompat, Material Components, ConstraintLayout, Preference.
- Сборка: Gradle wrapper из репозитория (`gradlew.bat` / `gradlew`), каталог версий `gradle/libs.versions.toml`.
- SDK: `compileSdk 36`, `targetSdk 35`, `minSdk 26`.
- Распознавание: клиент OpenAI Java (`com.openai`), работает и с совместимыми провайдерами.

## Направление и ограничения проекта

- Сохранять существующий стек: Java и Android XML. Не переводить UI на Compose и не вводить новый UI-фреймворк без явной просьбы пользователя.
- Старый дизайн клавиатуры заменяется на новый инкрементально; источник истины по визуалу — `voicekb-disign.pen`.
- Текущий приоритет — сама клавиатура: разметка, состояния, цвета, сенсорное поведение, жизненный цикл IME. Вторичные экраны меняются только когда этого требует задача.
- Не удалять существующее поведение без явной просьбы.
- Избегать лишней работы при старте IME: переключение клавиатуры должно ощущаться мгновенным.
- Предпочитать системные для Android паттерны взаимодействия там, где это практично.

## Команды и проверки перед завершением

Запускать из корня репозитория:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
git diff --check
```

Отладочный APK: `app\build\outputs\apk\debug\app-debug.apk`.

## Структура кода

- Основной сервис клавиатуры: `app/src/main/java/com/idefant/voicekb/core/VoiceKBInputMethodService.java`. Это крупный класс, держащий состояние клавиатуры, запись, распознавание и рендер состояний в одном месте; новую логику клавиатуры добавляй туда, где уже живёт смежное состояние, а не разноси по мелким классам без необходимости.
- Настройки, онбординг и статистика использования вынесены в отдельные пакеты (`settings`, `onboarding`, `usage`).
- Общие утилиты — в `VoiceKBUtils.java`.

## Конвенции

- Ключи `SharedPreferences` объявляются как строковые константы с префиксом `com.idefant.voicekb.` (например, `com.idefant.voicekb.return_to_previous_keyboard`). Новый ключ — новая константа, а не строковый литерал по месту.
- Скрытые элементы управления сохраняют занимаемое место в разметке, чтобы геометрия клавиатуры не «прыгала» между состояниями. Недоступное действие показывается через неактивный вид, а не через удаление кнопки.
- Геометрия кнопок остаётся стабильной при нажатиях и смене состояний.
- Тексты для пользователя берутся из `res/values/strings.xml`; переводы — в `values-de`, `values-es`, `values-pt`, `values-ru`. Не хардкодить пользовательские строки в коде.
- Строковый ресурс удаляется вместе с его последним использованием. Убрал `R.string.X` / `@string/X` из кода и разметки — в том же изменении удали строку из `values/strings.xml` и из всех локалей (`values-de`, `values-es`, `values-pt`, `values-ru`). Неиспользуемых переводов в репозитории быть не должно; исключение — строка, заведённая заранее под фичу, которая уже в работе.
- Светлая и тёмная темы поддерживаются параллельно: тёмные варианты ресурсов — в `res/drawable-night/` и `res/values-night/`. При изменении цветов и drawable клавиатуры проверять обе темы.

## Дизайн экранов настроек

- Экраны настроек и другие вторичные экраны (полные настройки, API-настройки, промпты, статистика использования, онбординг) собираются из стандартных компонентов Android без кастомного UI-фреймворка: главный экран настроек — на AndroidX `Preference` (`PreferenceScreen` / `PreferenceCategory` / `SwitchPreference` / `ListPreference` и т.п.), остальные — на Material Components и системных виджетах (`MaterialButton`, `Switch`, `Spinner`, `EditText`, `RadioButton`, `RecyclerView`, `ScrollView`).
- Дизайн-система этих экранов сделана на основе Material Design 3 (Material You): базовая тема `Theme.Material3.Light` / `Theme.Material3.Dark` (`res/values/themes.xml`, `res/values-night/themes.xml`), шрифт Inter, акцент `colorPrimary` = `@color/voicekb_blue` (#29B6F6). Заголовки секций и активные элементы — акцентным цветом, вторичный текст — `?android:attr/textColorSecondary`, разделители и опасные действия — из `res/values/colors.xml`.
- Списки строятся на `RecyclerView` с плоскими элементами и разделителями, без карточек; пустое состояние — центрированный курсивный текст (образец — экран статистики использования).
- Макеты этих экранов ведутся в `voicekb-disign.pen` рядом с макетами клавиатуры, в двух вариантах — Light и Dark; при правках проверять обе темы.

## Локальная среда

Ожидаемые инструменты Windows:

- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- ADB: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Эмулятор: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe`
- AVD: `Medium_Phone_API_36.0`

Ожидаемые приложения в эмуляторе:

- Markor (текстовое поле для проверки): `net.gsantner.markor`, вкладка `QuickNote`
- AnySoftKeyboard (обычная клавиатура по умолчанию): `com.menny.android.anysoftkeyboard/.SoftKeyboard`
- VoiceKB release: `com.idefant.voicekb`
- VoiceKB debug (IME): `com.idefant.voicekb.debug/com.idefant.voicekb.core.VoiceKBInputMethodService`

## Эмулятор и ручная проверка

UI-изменения проверяются на эмуляторе. Запуск эмулятора GUI-командой на уровне хоста (foreground-запуск в песочнице может падать с ошибкой доступа к `snapshot.lock.lock`):

```powershell
Start-Process `
  -FilePath "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -ArgumentList '-avd','Medium_Phone_API_36.0','-no-snapshot-save'
```

Дождаться загрузки (`sys.boot_completed` = `1`) и при необходимости снять блокировку экрана:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell getprop sys.boot_completed
& $adb -s emulator-5554 shell wm dismiss-keyguard
```

Установить VoiceKB и переключиться на неё:

```powershell
& $adb -s emulator-5554 install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s emulator-5554 shell ime enable com.idefant.voicekb.debug/com.idefant.voicekb.core.VoiceKBInputMethodService
& $adb -s emulator-5554 shell ime set com.idefant.voicekb.debug/com.idefant.voicekb.core.VoiceKBInputMethodService
```

После тестов вернуть обычную клавиатуру по умолчанию, если следующий тест сразу не требует VoiceKB:

```powershell
& $adb -s emulator-5554 shell ime enable com.menny.android.anysoftkeyboard/.SoftKeyboard
& $adb -s emulator-5554 shell ime set com.menny.android.anysoftkeyboard/.SoftKeyboard
```

Открыть текстовое поле для проверки (Markor `QuickNote`), снять состояние UI, сделать скриншот и дамп иерархии:

```powershell
& $adb -s emulator-5554 shell am start -n net.gsantner.markor/.activity.MainActivity
& $adb -s emulator-5554 shell settings get secure default_input_method
& $adb -s emulator-5554 shell dumpsys input_method | Select-String 'mInputShown'
& $adb -s emulator-5554 shell screencap -p /data/local/tmp/voicekb-screen.png
& $adb -s emulator-5554 pull /data/local/tmp/voicekb-screen.png '.\voicekb-screen.png'
```

Проверка тем:

```powershell
& $adb -s emulator-5554 shell cmd uimode night yes
& $adb -s emulator-5554 shell cmd uimode night no
```

Экранные координаты для скриптовых тапов — вспомогательные ориентиры, а не контракт; при изменении разметки перепроверять иерархию UI. Временные скриншоты и дампы удалять после осмотра, чтобы они не оставались в рабочем дереве Git. Не использовать звуковые уведомления для автоматических проверок; звук — только когда пользователю нужно физически вернуться, одобрить или посмотреть результат. Установка на физическое устройство — только по явной просьбе пользователя.

## Ключевые ручные сценарии

При изменениях клавиатуры и жизненного цикла IME проверять как минимум:

1. Переключение с AnySoftKeyboard на VoiceKB, в том числе с включённой мгновенной записью.
2. Запись, завершение и вставку текста.
3. При включённом возврате к предыдущей клавиатуре — AnySoftKeyboard становится активной; при выключенном — VoiceKB остаётся видимой.
4. Открытие списка недавних приложений и возврат в исходное приложение.
5. Блокировку и разблокировку при выбранной VoiceKB.
6. Открытие полных настроек и возврат.
7. Открытие и закрытие быстрых настроек; переход к выбору языка и его закрытие — VoiceKB остаётся видимой.
8. Выбор файла: открытие, отмену, выбор.
9. Повторную отправку с корректным предыдущим аудио и без него.
10. Состояния записи, паузы, отправки, сброса и ожидания.
11. Повтор ключевых проверок в светлой и тёмной темах.

## Релизы

Версия задаётся двумя полями в `app/build.gradle`:

- `versionCode` — техническое число обновления; Android по нему решает, может ли APK обновить установленный. Его нужно увеличивать для каждого публичного билда (alpha, beta, RC, stable, hotfix), даже когда `versionName` переходит от предзрелизного имени к стабильному. Если его не увеличить, установка поверх уже установленной копии может быть отклонена — это самая опасная ошибка.
- `versionName` — человекочитаемое имя версии. Не влияет на приём обновления, но при неизменности вводит пользователей в заблуждение. Лучше обновлять оба поля вместе.

Релизы GitHub создаются пушем тега, начинающегося с `v` (например, `v0.2.0-beta.1`, `v0.2.0`).

`CHANGELOG.md` ведётся только для стабильных версий. Для стабильного тега в нём должен быть заголовок, совпадающий с именем тега (`## v0.2.0`) — по нему workflow находит нужную секцию описания релиза.

Для нестабильных версий (`alpha`, `beta`, `rc`) `CHANGELOG.md` не обновляется: их изменения попадают в changelog позже, одной записью стабильного релиза. Секции для такого тега в файле нет, и workflow сам собирает описание релиза из списка коммитов с предыдущего тега.

Чек-лист выпуска:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
git diff --check
git tag v0.2.0
git push
git push origin v0.2.0
```

GitHub Actions соберёт подписанный релизный APK и приложит его к релизу. Отладочные и релизные сборки используют разные пакеты (`com.idefant.voicekb.debug` и `com.idefant.voicekb`), поэтому debug-APK можно ставить рядом с релизной версией на одном телефоне.

## Appium MCP

Appium MCP может быть настроен в среде пользователя — это необязательное удобство. Если его инструменты недоступны в сессии, достаточно ADB, скриншотов, `uiautomator` и `dumpsys`.

## Что не коммитить

Одобрения команд Codex, конфигурация Appium MCP, пути к Android SDK и локальные файлы AVD — это состояние машины пользователя. В репозиторий не добавлять.
