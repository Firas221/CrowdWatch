/* CrowdWatch screen language: English or Arabic (right to left).
   The pages are written in English; in Arabic mode every text on the screen is looked up in the
   dictionary below as it appears (the pages redraw every few seconds, so new text is translated
   as it is added). Names that come from the venue file (areas, units, the venue) and identifiers
   such as I1003 stay as they are. Sentences written by the server are translated where they
   match a known pattern; others stay in English. Switching back to English restores the text. */
(function () {
  'use strict';
  // exact texts and phrases; a phrase with a space is also replaced inside a longer text
  var AR = {
    // header, sign in, navigation
    'CrowdWatch console': 'وحدة تحكم CrowdWatch', 'Control room console': 'وحدة غرفة التحكم', 'Officer name': 'اسم الضابط',
    'Control key': 'مفتاح التحكم', 'Sign in and check in': 'تسجيل الدخول وتأكيد الحضور', 'Sign in': 'تسجيل الدخول', 'Sign out': 'تسجيل الخروج',
    'User name': 'اسم المستخدم', 'Password': 'كلمة المرور', 'Use the control key instead': 'استخدام مفتاح التحكم بدلاً من ذلك',
    'Use my user name and password': 'استخدام اسم المستخدم وكلمة المرور',
    'Sign out and forget the key': 'تسجيل الخروج ونسيان المفتاح', 'The key is kept in this browser only. Scouts use': 'يُحفظ المفتاح في هذا المتصفح فقط. يستخدم الكشافة',
    'the plain dashboard is at': 'واللوحة البسيطة على', 'leave empty if the server has no key': 'اتركه فارغاً إذا لم يكن للخادم مفتاح',
    'e.g. Khalid': 'مثال: خالد', 'Search': 'بحث', 'Search areas, incidents (I1003), units': 'ابحث عن المناطق أو البلاغات (I1003) أو الوحدات',
    'Light': 'فاتح', 'Dark': 'داكن', 'Alerts': 'التنبيهات', 'LIVE': 'مباشر', 'OFFLINE': 'غير متصل',
    'Overview': 'نظرة عامة', 'Areas and crowd': 'المناطق والحشود', 'Incidents': 'البلاغات', 'Units and scouts': 'الوحدات والكشافة',
    'Lost persons': 'المفقودون', 'SOS and evacuation': 'الاستغاثة والإخلاء', 'Venue settings': 'إعدادات الموقع', 'System': 'النظام',
    'Server online': 'الخادم متصل', 'Server not answering': 'الخادم لا يستجيب', 'Automatic dispatch ON': 'الإرسال التلقائي مفعّل',
    'Automatic dispatch OFF': 'الإرسال التلقائي متوقف', 'Automatic dispatch': 'الإرسال التلقائي', 'archived incidents': 'بلاغات مؤرشفة',
    'devices online': 'أجهزة متصلة', 'Check in now': 'تأكيد الحضور الآن', 'Check in as': 'تأكيد الحضور باسم', 'On watch': 'في المناوبة',
    'Officer': 'الضابط', 'English': 'English', 'العربية': 'العربية',
    // levels, priorities, statuses
    'LOW': 'منخفض', 'MODERATE': 'متوسط', 'HIGH': 'مرتفع', 'CRITICAL': 'حرج', 'NORMAL': 'عادي',
    'Low': 'منخفض', 'Moderate': 'متوسط', 'High': 'مرتفع', 'Critical': 'حرج', 'Normal': 'عادي',
    'WAITING': 'بالانتظار', 'ASSIGNED': 'مُسند', 'ON_SCENE': 'في الموقع', 'RESOLVED': 'محلول',
    'AVAILABLE': 'متاح', 'BUSY': 'مشغول', 'OFF_DUTY': 'خارج الخدمة', 'UNREACHABLE': 'لا يمكن الوصول',
    'Available': 'متاح', 'Busy': 'مشغول', 'Off duty': 'خارج الخدمة', 'Unreachable': 'لا يمكن الوصول',
    'Open': 'مفتوح', 'Resolved': 'محلول', 'On scene': 'في الموقع', 'Waiting for a unit': 'بانتظار وحدة', 'waiting for a unit': 'بانتظار وحدة',
    'IN PROGRESS': 'جارٍ', 'MISSING': 'مفقود', 'Automatic': 'تلقائي', 'AUTO - review': 'تلقائي - للمراجعة',
    // overview
    'People on site': 'الأشخاص في الموقع', 'Areas HIGH or CRITICAL': 'مناطق مرتفعة أو حرجة', 'Open incidents': 'البلاغات المفتوحة',
    'Units available': 'الوحدات المتاحة', 'Awaiting review': 'بانتظار المراجعة', 'automatic dispatches': 'إرسالات تلقائية',
    'all areas below HIGH': 'كل المناطق دون المرتفع', 'Live floor plan': 'المخطط المباشر', 'Live alerts': 'التنبيهات المباشرة',
    'View all': 'عرض الكل', 'No alerts yet.': 'لا توجد تنبيهات بعد.', 'Map': 'الخريطة', 'Lanes': 'المسارات',
    'Venue map with live crowd levels': 'خريطة الموقع بمستويات الحشود المباشرة', 'Occupancy - last': 'الإشغال - آخر',
    'over the last hour': 'خلال الساعة الأخيرة', 'All areas by load': 'كل المناطق حسب الحمل',
    'The venue drawing: colour is the crowd level, blue dots are units, dashed green outlines are exits': 'مخطط الموقع: اللون مستوى الحشد، والنقاط الزرقاء وحدات، والحدود الخضراء المتقطعة مخارج',
    'Each row is a sector in walking order - colour is the crowd level, a pulsing outline means act now': 'كل صف قطاع بترتيب المشي - اللون مستوى الحشد، والحد النابض يعني تصرّف الآن',
    'Click an area to enter a head count': 'انقر على منطقة لإدخال عدد الأشخاص',
    // areas
    'Area': 'المنطقة', 'Sector': 'القطاع', 'Sectors': 'القطاعات', 'People': 'الأشخاص', 'Load': 'الحمل', 'Level': 'المستوى', 'Trend': 'الاتجاه',
    'Trend:': 'الاتجاه:', 'Units': 'الوحدات', 'Save count': 'حفظ العدد', 'Leads to': 'يؤدي إلى', 'staff only': 'للموظفين فقط',
    'Crowd flow': 'تدفق الحشد', 'hold flow': 'إيقاف التدفق', 'people a minute': 'شخص في الدقيقة', 'Count check': 'مطابقة العد',
    'Reset after a recount': 'إعادة الضبط بعد إعادة العد', 'People counted now': 'الأشخاص المعدودون الآن', 'do not add up': 'لا تتطابق',
    'no sensor data: the count may be out of date': 'لا توجد بيانات من الحساسات: قد يكون العدد قديماً',
    'flow held by units next to it': 'التدفق موقوف بوحدات مجاورة', 'sensor count doubtful:': 'عدد الحساس مشكوك فيه:',
    // incidents
    'Report incident': 'الإبلاغ عن حادثة', 'Report': 'إبلاغ', 'What': 'ماذا', 'Where': 'أين', 'Details': 'التفاصيل', 'Details:': 'التفاصيل:',
    'Contact': 'التواصل', 'Language': 'اللغة', 'optional': 'اختياري', 'Cancel': 'إلغاء', 'Close': 'إغلاق', 'Priority': 'الأولوية',
    'Status': 'الحالة', 'Status:': 'الحالة:', 'Unit': 'الوحدة', 'Reported': 'وقت البلاغ', 'Reporter': 'المُبلّغ', 'Reporter contact': 'تواصل المُبلّغ',
    'All open': 'كل المفتوحة', 'Review': 'مراجعة', 'Most urgent first. Click one for details, route and actions.': 'الأكثر إلحاحاً أولاً. انقر على بلاغ للتفاصيل والمسار والإجراءات.',
    'It is sent to the nearest free unit with the right skills': 'يُرسل إلى أقرب وحدة متاحة بالمهارات المناسبة',
    'What you see, who is involved': 'ما تراه ومن المعنيون', 'Approve dispatch': 'اعتماد الإرسال', 'Change unit': 'تغيير الوحدة',
    'Confirm on scene': 'تأكيد الوصول', 'Escalate': 'تصعيد', 'Reassign': 'إعادة إسناد', 'Send another unit': 'إرسال وحدة أخرى',
    'Resolve incident': 'إغلاق البلاغ', 'Resolve with a note': 'إغلاق مع ملاحظة', 'What was done': 'ما الذي تم', 'Timeline': 'التسلسل الزمني',
    'Route': 'المسار', 'Route:': 'المسار:', 'Walk': 'المشي', 'min walk': 'دقيقة مشي', 'no unit available': 'لا توجد وحدة متاحة',
    'reviewed by': 'روجع بواسطة', 'awaiting an officer': 'بانتظار ضابط', 'sent, not yet acknowledged': 'أُرسل ولم يُؤكَّد بعد',
    'acknowledged': 'تم التأكيد', 'on scene': 'في الموقع', 'not acknowledged - reminder sent': 'لم يُؤكَّد - أُرسل تذكير',
    // units
    'Unit and device': 'الوحدة والجهاز', 'Skills:': 'المهارات:', 'No task - ready to be sent': 'لا مهمة - جاهز للإرسال',
    '- never connected': '- لم يتصل أبداً', '- last contact': '- آخر اتصال', 'Device': 'الجهاز', 'Scout devices': 'أجهزة الكشافة',
    'Scouts open': 'يفتح الكشافة', 'tasks not yet acknowledged': 'مهام لم تُؤكَّد بعد',
    // lost persons
    'Lost person': 'شخص مفقود', 'Report a lost person': 'الإبلاغ عن شخص مفقود', 'Last seen at': 'آخر مكان شوهد فيه', 'Description': 'الوصف',
    'Age, clothes, hair, language, who they were with': 'العمر والملابس والشعر واللغة ومن كان معه', 'Send lookout': 'إرسال نشرة بحث',
    'No open lost-person cases.': 'لا توجد حالات مفقودين مفتوحة.', 'Found someone?': 'وجدت شخصاً؟', 'Person found...': 'شخص تم العثور عليه...',
    'Record found person': 'تسجيل شخص تم العثور عليه', 'Age, clothes, what they say': 'العمر والملابس وما يقوله',
    'Mark found': 'تحديد كموجود', 'Found at': 'عُثر عليه في', 'Open case': 'فتح الحالة', 'Meeting point': 'نقطة التجمع', 'the meeting point': 'نقطة التجمع',
    'This venue has no lost-person category.': 'لا يوجد في هذا الموقع تصنيف للمفقودين.', 'last seen': 'آخر مشاهدة',
    'For a person found alone. They are recorded as found, a unit looks after them, and open cases are listed to compare.': 'لشخص وُجد وحده. يُسجَّل كموجود وتعتني به وحدة وتُعرض الحالات المفتوحة للمقارنة.',
    // emergency
    'SOS': 'استغاثة', 'Send SOS': 'إرسال استغاثة', 'SOS - someone needs help now': 'استغاثة - شخص يحتاج مساعدة الآن',
    'A CRITICAL incident: the nearest free units are sent at once': 'بلاغ حرج: تُرسل أقرب الوحدات المتاحة فوراً',
    'What is happening': 'ماذا يحدث', 'SOS from the control room': 'استغاثة من غرفة التحكم', 'Evacuation': 'الإخلاء', 'Evacuation plan': 'خطة الإخلاء',
    'Zone': 'النطاق', 'Choose an area or sector': 'اختر منطقة أو قطاعاً', 'Preview plan': 'معاينة الخطة', 'Start evacuation': 'بدء الإخلاء',
    'End evacuation': 'إنهاء الإخلاء', 'Open plan': 'فتح الخطة', 'Dismiss': 'تجاهل', 'From': 'من', 'Exit': 'المخرج', 'Exits': 'المخارج',
    'All out': 'خروج الجميع', 'in zone': 'داخل النطاق', 'Every unit is told at once and public routes avoid the zone': 'تُبلَّغ كل الوحدات فوراً وتتجنب المسارات العامة النطاق',
    'Estimated time until everyone in the zone is out:': 'الوقت المقدّر حتى يخرج الجميع من النطاق:', 'Broadcast to every unit': 'بث إلى كل الوحدات',
    'Send broadcast': 'إرسال البث', 'Message': 'الرسالة', 'Sent to every device and the radio gateway': 'يُرسل إلى كل جهاز وإلى بوابة اللاسلكي',
    'Alarm': 'إنذار', 'Alarm input': 'إدخال إنذار', 'Raise alarm': 'إطلاق الإنذار', 'Fire zones': 'مناطق الحريق', 'Areas': 'المناطق',
    'For a fire or other alarm panel without a connection (panels can POST /api/alarm). A unit is sent to check at once and an evacuation of the sector is proposed.': 'للوحة إنذار حريق أو غيرها بلا اتصال (يمكن للوحات الإرسال إلى ‎/api/alarm). تُرسل وحدة للتحقق فوراً ويُقترح إخلاء القطاع.',
    'Public messages': 'الرسائل العامة', 'Public screens and PA': 'الشاشات العامة والإذاعة', 'Approve and send': 'اعتماد وإرسال', 'Reject': 'رفض',
    'No drafts waiting.': 'لا توجد مسودات بالانتظار.', 'Your own message': 'رسالتك', 'Send to screens and PA': 'إرسال إلى الشاشات والإذاعة',
    'e.g. Gate C is closed, please use Gate B': 'مثال: البوابة C مغلقة، يرجى استخدام البوابة B', 'Recent:': 'الأخيرة:',
    'The system drafts messages (busy areas, evacuation, long-missing persons). Nothing reaches the public until an officer approves it.': 'يكتب النظام مسودات الرسائل (المناطق المزدحمة، الإخلاء، المفقودون لفترة طويلة). لا يصل شيء إلى الجمهور قبل اعتماد ضابط.',
    'EVACUATION IN PROGRESS:': 'الإخلاء جارٍ:',
    'The server is not answering. The figures below are from the last good update.': 'الخادم لا يستجيب. الأرقام أدناه من آخر تحديث ناجح.',
    // alerts
    'All': 'الكل', 'No alerts of this kind.': 'لا توجد تنبيهات من هذا النوع.', 'Newest': 'الأحدث', 'Older alerts are in the reports and the database': 'التنبيهات الأقدم في التقارير وقاعدة البيانات',
    // settings
    'Supervision': 'الإشراف', 'Check-in every': 'تأكيد الحضور كل', 'Crowd levels': 'مستويات الحشد', 'Acknowledge within': 'التأكيد خلال',
    'Archive after': 'الأرشفة بعد', 'Radio gateway': 'بوابة اللاسلكي', 'not configured': 'غير مُعدّة', 'configured': 'مُعدّة',
    'These settings come from the venue file. Edit it and restart to change them.': 'تأتي هذه الإعدادات من ملف الموقع. عدّله وأعد التشغيل لتغييرها.',
    'Incident categories': 'تصنيفات البلاغات', 'Code': 'الرمز', 'Label': 'الوصف', 'This browser': 'هذا المتصفح', 'Switch light or dark': 'تبديل الفاتح والداكن',
    'Plain dashboard': 'اللوحة البسيطة', 'Automatic rules': 'القواعد التلقائية', 'Sensor checks': 'فحص الحساسات', 'Follow-through': 'المتابعة',
    'Reinforcement': 'التعزيز', 'Scheduled peaks': 'الذروات المجدولة', 'Shift changes': 'تبديل المناوبات', 'Handover report': 'تقرير التسليم',
    'Handover report now': 'تقرير التسليم الآن', 'Current situation for the officer coming on watch': 'الوضع الحالي للضابط المستلم', 'Download': 'تنزيل',
    'They act while automatic dispatch is ON (sensor checks, shifts and handover reports always run).': 'تعمل أثناء تفعيل الإرسال التلقائي (فحص الحساسات والمناوبات وتقارير التسليم تعمل دائماً).',
    'none yet': 'لا شيء بعد', 'now': 'الآن', 'Start': 'ابدأ', 'Enter': 'إدخال', 'Acknowledge': 'تأكيد الاستلام', 'Messages': 'الرسائل', 'Reason': 'السبب',
    'Bring to': 'أحضره إلى', 'Mark': 'تحديد',
    // accounts and audit
    'Users and access': 'المستخدمون والصلاحيات', 'Users': 'المستخدمون', 'Role': 'الدور', 'Can sign in': 'يمكنه الدخول', 'Save user': 'حفظ المستخدم',
    'Audit log': 'سجل التدقيق', 'Show audit log': 'عرض سجل التدقيق', 'Time': 'الوقت', 'User': 'المستخدم', 'Action': 'الإجراء', 'Result': 'النتيجة',
    'Scout device codes': 'رموز أجهزة الكشافة', 'Issue new code': 'إصدار رمز جديد', 'Cancel code': 'إلغاء الرمز', 'Signed in as': 'مسجّل باسم',
    'OPERATOR': 'مشغّل', 'OFFICER': 'ضابط', 'ADMIN': 'مسؤول', 'DESKTOP': 'سطح المكتب', 'DEVICE': 'جهاز', 'SCOUT': 'كشاف',
    'OK': 'تم', 'REFUSED': 'مرفوض', 'empty keeps the current password': 'اتركها فارغة للإبقاء على كلمة المرور الحالية',
    'Each scout phone gets its own code that only works for its unit. A new code cancels the old one.': 'يحصل كل هاتف كشاف على رمز خاص يعمل لوحدته فقط. الرمز الجديد يلغي القديم.',
    'Every change made here or on the network is recorded with the name of the person who made it.': 'يُسجَّل كل تغيير هنا أو عبر الشبكة باسم من قام به.',
    // scout page
    'CrowdWatch Scout': 'كشاف CrowdWatch', 'Enter your unit ID (and the scout key you were given) to start.': 'أدخل رقم وحدتك (ورمز الجهاز أو مفتاح الكشافة) للبدء.',
    'Unit ID, e.g. A03': 'رقم الوحدة، مثال A03', 'Device code or scout key': 'رمز الجهاز أو مفتاح الكشافة', 'Scout key': 'مفتاح الكشافة',
    'No task. Stand by.': 'لا مهمة. ابقَ على أهبة الاستعداد.', 'Person FOUND (I am with them)': 'تم العثور على الشخص (أنا معه)',
    'Found - I am with them': 'تم العثور عليه - أنا معه', 'I found someone who is lost': 'وجدت شخصاً تائهاً', 'PLEASE ACKNOWLEDGE': 'يرجى تأكيد الاستلام',
    'No connection - retrying': 'لا يوجد اتصال - إعادة المحاولة', 'No task.': 'لا مهمة.',
    'Key not accepted: tap Change unit and enter the scout key.': 'المفتاح غير مقبول: اضغط تغيير الوحدة وأدخل الرمز.',
    'Closing note (optional)': 'ملاحظة الإغلاق (اختياري)', 'Send SOS from your position?': 'إرسال استغاثة من موقعك؟', 'What is happening? (optional)': 'ماذا يحدث؟ (اختياري)',
    'Describe the person you found (age, clothes, language)': 'صف الشخص الذي وجدته (العمر، الملابس، اللغة)', 'Their contact or companion (optional)': 'وسيلة تواصله أو مرافقه (اختياري)',
    'Stay with the person.': 'ابقَ مع الشخص.', 'you are there': 'أنت هناك', 'ask the control room': 'اسأل غرفة التحكم', '(acknowledged)': '(تم التأكيد)',
    // short words and phrases inside longer lines
    'steady': 'ثابت', 'live': 'مباشر', 'Evacuate': 'إخلاء', 'EVACUATING': 'جارٍ إخلاؤها', 'checked in': 'أكّد الحضور', 'min ago': 'دقيقة مضت',
    'min, then the task is sent again': 'دقيقة، ثم تُرسل المهمة مجدداً', 'min resolved': 'دقيقة بعد الإغلاق', 'from': 'من',
    'drafts on (approval needed)': 'المسودات مفعّلة (تتطلب اعتماداً)', 'Signed in as': 'مسجّل باسم', 'signed in': 'سجّل الدخول',
    'not in use (no gate counter sends people in or out of the venue)': 'غير مستخدمة (لا يرسل أي عداد بوابات دخولاً أو خروجاً)',
    'a handover report is written at each': 'يُكتب تقرير تسليم عند كل',
    'only an officer starts it': 'لا يبدؤه إلا ضابط', 'meeting point': 'نقطة التجمع', 'units sent ahead for areas CRITICAL within': 'وحدات تُرسل مسبقاً للمناطق التي ستصبح حرجة خلال',
    'sector cover on': 'تغطية القطاعات مفعّلة', 'at CRITICAL any distance': 'عند الحرج من أي مسافة', 'they return to their posts': 'ثم تعود إلى مواقعها',
    'after it calms': 'بعد أن تهدأ', 'widening every': 'يتسع كل', 'stale after': 'قديمة بعد', 'min without data': 'دقيقة بلا بيانات'
  };
  // texts with numbers or names in them: [pattern, Arabic with $1, $2 ...]
  var PATTERNS = [
    [/^(\d+) of (\d+) devices online$/, '$1 من $2 أجهزة متصلة'],
    [/^(\d+) critical, (\d+) waiting$/, '$1 حرجة، $2 بالانتظار'],
    [/^of (\d+) on duty$/, 'من $1 في الخدمة'],
    [/^([\d,]+)% of ([\d,]+) capacity(.*)$/, '$1% من سعة $2$3'],
    [/^LIVE (\d\d:\d\d)$/, 'مباشر $1'],
    [/^Officer (.+)$/, 'الضابط $1'],
    [/^On watch - (.*)$/, 'في المناوبة - $1'],
    [/^On watch: (.*)$/, 'في المناوبة: $1'],
    [/^Check-in OVERDUE - click to check in$/, 'تأخر تأكيد الحضور - انقر للتأكيد'],
    [/^checked in (\d+) min ago(.*)$/, 'أكّد الحضور قبل $1 دقيقة$2'],
    [/^Unit (.+)$/, 'الوحدة $1'],
    [/^(\d+) min$/, '$1 دقيقة'],
    [/^(\d+) min walk$/, '$1 دقيقة مشي'],
    [/^(\d+) people$/, '$1 شخص'],
    [/^(\d+) units$/, '$1 وحدات'],
    [/^missing (\d+) min$/, 'مفقود منذ $1 دقيقة'],
    [/^EVACUATION IN PROGRESS: (.*)$/, 'الإخلاء جارٍ: $1'],
    [/^EVACUATION of (.*) in progress\. Public routes avoid the zone\.$/, 'إخلاء $1 جارٍ. المسارات العامة تتجنب النطاق.'],
    [/^EVACUATION PROPOSED for ([^:]*): (.*)\. You decide\.$/, 'إخلاء مقترح لـ $1: $2. القرار لك.'],
    [/^Refused: (.*)$/, 'مرفوض: $1'],
    [/^(.*) at (\d+)%$/, '$1 عند $2%'],
    [/^your role (\w+) cannot use (.*) \(needs (\w+)\)$/, 'دورك $1 لا يسمح باستخدام $2 (يتطلب $3)'],
    [/^Checked in: (.*)$/, 'تم تأكيد الحضور: $1'],
    [/^(\d+) automatic dispatch(?:es)? awaiting review$/, '$1 إرسال تلقائي بانتظار المراجعة'],
    [/^CrowdWatch \/ (.*) \/ ([^/]+)$/, function (m, venue, page) { return 'CrowdWatch / ' + venue + ' / ' + (AR[page] || page); }],
    [/^Rule: send (.*?) Switching it is logged with your name\.$/, 'القاعدة: إرسال $1. يُسجَّل التبديل باسمك.'],
    [/^Rule: send (.*)$/, 'القاعدة: إرسال $1'],
    [/^MODERATE from (\d+)%, HIGH from (\d+)%, CRITICAL from (\d+)%$/, 'متوسط من $1%، مرتفع من $2%، حرج من $3%'],
    [/^(\S+) has not acknowledged (I\d+)$/, 'لم تؤكد $1 استلام $2'],
    [/^(.+), checked in (\d+) min ago(.*)$/, '$1، أكّد الحضور قبل $2 دقيقة$3'],
    [/^Switching it is logged with your name\.$/, 'يُسجَّل التبديل باسمك.'],
    [/^(\d+) people a minute$/, '$1 شخص في الدقيقة'],
    [/^([+-]\d+%?)$/, '$1']
  ];
  var phrases = Object.keys(AR).filter(function (k) { return k.indexOf(' ') > 0 && k.length >= 7; }).sort(function (a, b) { return b.length - a.length; });
  var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, CODE: 1, PRE: 1 };
  var ATTRS = ['placeholder', 'title', 'aria-label'];
  var origText = new WeakMap(), origAttr = new WeakMap();
  var lang = 'en';
  try { lang = localStorage.getItem('cw.lang') || 'en'; } catch (e) { /* private window */ }

  function translate(s) {
    if (s == null) return null;
    var t = s.trim();
    if (!t) return null;
    if (Object.prototype.hasOwnProperty.call(AR, t)) return s.replace(t, AR[t]);
    var out = t, hit = false;
    for (var k = 0; k < PATTERNS.length; k++) if (PATTERNS[k][0].test(t)) { out = t.replace(PATTERNS[k][0], PATTERNS[k][1]); hit = true; break; }
    if (!/[A-Za-z]{3}/.test(out)) return hit ? s.replace(t, out) : null;
    for (var p = 0; p < phrases.length; p++) {
      if (out.indexOf(phrases[p]) >= 0) { out = out.split(phrases[p]).join(AR[phrases[p]]); hit = true; }
    }
    return hit ? s.replace(t, out) : null;
  }
  function skipped(el) {
    for (; el && el !== document.body; el = el.parentElement) {
      if (SKIP[el.tagName] || (el.classList && (el.classList.contains('mono') || el.classList.contains('no-i18n')))) return true;
    }
    return false;
  }
  function doText(n) {
    if (skipped(n.parentElement)) return;
    var o = origText.has(n) ? origText.get(n) : n.nodeValue;
    if (lang !== 'ar') { if (origText.has(n) && n.nodeValue !== o) n.nodeValue = o; return; }
    if (origText.has(n) && n.nodeValue !== translate(o)) o = n.nodeValue;   // the page changed the text since
    var t = translate(o);
    if (t != null && t !== n.nodeValue) { origText.set(n, o); n.nodeValue = t; }
  }
  function doAttrs(el) {
    var saved = origAttr.get(el) || {};
    ATTRS.forEach(function (a) {
      if (!el.hasAttribute(a)) return;
      var cur = el.getAttribute(a), o = saved[a] != null ? saved[a] : cur;
      if (lang !== 'ar') { if (saved[a] != null && cur !== o) el.setAttribute(a, o); return; }
      if (saved[a] != null && cur !== o && cur !== translate(o)) o = cur;   // the page changed it since
      var t = translate(o);
      if (t != null) { saved[a] = o; if (cur !== t) el.setAttribute(a, t); }
    });
    origAttr.set(el, saved);
  }
  function walk(root) {
    if (!root) return;
    if (root.nodeType === 3) { doText(root); return; }
    if (root.nodeType !== 1 || SKIP[root.tagName]) return;
    doAttrs(root);
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
    for (var n = w.nextNode(); n; n = w.nextNode()) { if (n.nodeType === 3) doText(n); else doAttrs(n); }
  }
  var busy = false;
  var observer = new MutationObserver(function (list) {
    if (lang !== 'ar' || busy) return;
    busy = true;
    try {
      list.forEach(function (m) {
        if (m.type === 'characterData') doText(m.target);
        else if (m.type === 'attributes') doAttrs(m.target);
        else m.addedNodes.forEach(walk);
      });
      observer.takeRecords();   // drop the records of our own changes, or they would be handled again
    } finally { busy = false; }
  });
  function set(l) {
    lang = l === 'ar' ? 'ar' : 'en';
    try { localStorage.setItem('cw.lang', lang); } catch (e) { /* private window */ }
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
    busy = true;
    try { walk(document.body); observer.takeRecords(); } finally { busy = false; }
    document.querySelectorAll('[data-lang-btn]').forEach(function (b) { b.textContent = lang === 'ar' ? 'English' : 'العربية'; });
    try { document.dispatchEvent(new Event('cwlang')); } catch (e) { /* very old browser */ }
  }
  /** Translates one string for scripts (prompts and confirms). */
  window.cwT = function (s) { if (lang !== 'ar') return s; var t = translate(s); return t == null ? s : t; };
  window.cwLang = { get: function () { return lang; }, set: set, toggle: function () { set(lang === 'ar' ? 'en' : 'ar'); } };
  function init() {
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
    document.addEventListener('click', function (e) { if (e.target.closest && e.target.closest('[data-lang-btn]')) window.cwLang.toggle(); });
    set(lang);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
