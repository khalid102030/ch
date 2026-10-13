# مترجم الاجتماعات — المستودع

هذا المستودع يعطيك شيئين من نفس الملفات:
1. **رابط ويب https** (عبر GitHub Pages) — يعمل على اللابتوب والجوال والمايك يطلب الإذن طبيعياً.
2. **ملف APK لتطبيق أندرويد** — يُبنى تلقائياً عبر GitHub Actions.

## الرفع
- github.com ← New ← اسم المستودع (مثلاً `translator`) ← **Public** ← Create.
- Add file ← Upload files ← اسحب **محتويات** مجلد android-app (بما فيها مجلد `.github` المخفي) ← Commit changes.

> مهم: مجلد `.github` مخفي في بعض الأجهزة. لو ما ظهر، فعّل «إظهار الملفات المخفية»،
> أو أنشئه يدوياً: Add file ← Create new file ← اكتب الاسم `.github/workflows/build.yml` والصق محتواه.

## رابط الويب (GitHub Pages)
Settings ← Pages ← Source: Deploy from a branch ← Branch: `main` / `(root)` ← Save.
بعد دقيقة إلى دقيقتين يظهر الرابط: `https://اسمك.github.io/translator/`

## ملف APK
Actions ← Build APK ← انتظر ✓ ← Artifacts ← translator-apk ← بداخله `app-debug.apk`.

## تعديل التطبيق لاحقاً
عدّل `index.html` (للويب) و `app/src/main/assets/index.html` (للأندرويد) — نفس الملف.
