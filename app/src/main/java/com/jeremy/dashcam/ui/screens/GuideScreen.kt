package com.jeremy.dashcam.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jeremy.dashcam.R
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.ScreenHeader
import com.jeremy.dashcam.ui.theme.J

/** Car tips (Hebrew + English). [steps] are shown as a numbered list, [tip] as a highlighted note. */
private data class Tip(val icon: ImageVector, val tint: Color, val he: Triple<String, String, List<String>>, val en: Triple<String, String, List<String>>, val tipHe: String? = null, val tipEn: String? = null)

private val tips = listOf(
    Tip(Icons.Filled.TireRepair, J.Amber,
        Triple("החלפת גלגל (פנצ'ר)", "צעד אחר צעד, בבטחה", listOf(
            "עצור במקום ישר ובטוח, הרחק מהנתיב. הדלק אורות מצוקה, משוך בלם יד והכנס להילוך (או P).",
            "לבש אפוד זוהר והצב משולש אזהרה כ־30 מטר מאחורי הרכב (בכביש מהיר – רחוק יותר).",
            "הוצא את הגלגל הרזרבי, הג'ק ומפתח הברגים (בדרך כלל מתחת לרצפת תא המטען).",
            "כשהרכב עוד על הקרקע – שחרר כל בורג חצי סיבוב נגד כיוון השעון. לא להוציא עדיין.",
            "הצב את הג'ק בנקודת ההרמה המסומנת (חריץ או חץ בשולי הרכב, ליד הגלגל) והרם עד שהגלגל באוויר.",
            "הוצא את הברגים ואת הגלגל, הרכב את הרזרבי והברג את הברגים ביד.",
            "הורד את הרכב, ואז הדק את הברגים בצורת כוכב (לסירוגין, לא בסדר עגול) עד הסוף.",
            "בדוק לחץ אוויר ברזרבי בהקדם. גלגל חירום צר – לא יותר מ־80 קמ״ש ולמרחק קצר.",
        )),
        Triple("Changing a flat tyre", "Step by step, safely", listOf(
            "Stop on flat, safe ground away from traffic. Hazard lights on, handbrake on, in gear (or P).",
            "Put on a reflective vest and place the warning triangle about 30 m behind the car (further on a highway).",
            "Take out the spare, the jack and the wheel wrench (usually under the boot floor).",
            "With the car still on the ground, loosen each nut half a turn anticlockwise. Don't remove them yet.",
            "Place the jack at the marked lifting point (notch or arrow on the sill, near the wheel) and lift until the wheel is off the ground.",
            "Remove the nuts and the wheel, fit the spare and screw the nuts on by hand.",
            "Lower the car, then tighten the nuts fully in a star pattern (criss-cross, not in a circle).",
            "Check the spare's pressure soon. A thin space-saver spare: max 80 km/h, short distances only.",
        )),
        "לעולם אל תכניס יד או רגל מתחת לרכב שעומד על ג'ק.", "Never put a hand or leg under a car that is on a jack."),

    Tip(Icons.Filled.BatteryAlert, J.Red,
        Triple("התנעה בכבלים", "מצבר ריק – איך מחברים נכון", listOf(
            "העמד את הרכב התקין קרוב (בלי שהרכבים ייגעו), כבה את שניהם והוצא מפתחות.",
            "כבל אדום: לפלוס (+) של המצבר הריק.",
            "הקצה השני של האדום: לפלוס (+) של המצבר התקין.",
            "כבל שחור: למינוס (−) של המצבר התקין.",
            "הקצה השני של השחור: לחלק מתכת לא צבוע במנוע של הרכב התקוע, רחוק מהמצבר (לא למינוס שלו).",
            "התנע את הרכב התקין ותן לו לעבוד 2–3 דקות, ואז נסה להתניע את הרכב התקוע.",
            "אחרי שהתניע – נתק בסדר הפוך: שחור מהמתכת, שחור מהתקין, אדום מהתקין, אדום מהריק.",
            "סע לפחות 20–30 דקות כדי שהמצבר ייטען. אם זה חוזר – כנראה צריך מצבר חדש.",
        )),
        Triple("Jump-starting", "Flat battery – connecting it right", listOf(
            "Park the good car close (cars not touching), switch both off, keys out.",
            "Red cable: to the plus (+) of the flat battery.",
            "Other red end: to the plus (+) of the good battery.",
            "Black cable: to the minus (−) of the good battery.",
            "Other black end: to bare unpainted metal on the dead car's engine, away from the battery (not its minus).",
            "Start the good car, let it run 2–3 minutes, then try to start the dead car.",
            "Once running, disconnect in reverse: black from metal, black from good, red from good, red from flat.",
            "Drive at least 20–30 minutes to recharge. If it keeps happening, the battery probably needs replacing.",
        )),
        "אל תיתן לקצוות האדום והשחור לגעת זה בזה כשהם מחוברים.", "Never let the red and black clamps touch while connected."),

    Tip(Icons.Filled.OilBarrel, J.Amber,
        Triple("בדיקת שמן מנוע", "פעם בחודש ולפני נסיעה ארוכה", listOf(
            "חנה במקום ישר, כבה את המנוע וחכה 5–10 דקות שהשמן ירד.",
            "שלוף את מד השמן (ידית צהובה/כתומה בדרך כלל), נגב אותו והכנס עד הסוף.",
            "שלוף שוב: השמן צריך להיות בין הסימן MIN ל־MAX.",
            "אם חסר – הוסף מעט מסוג השמן שכתוב בספר הרכב, דרך המכסה עם סמל כד השמן, ובדוק שוב.",
            "שמן שחור מאוד או סמיך, או נורת שמן אדומה בלוח – לטיפול בהקדם.",
        )),
        Triple("Checking engine oil", "Monthly and before long trips", listOf(
            "Park on level ground, engine off, wait 5–10 minutes for the oil to settle.",
            "Pull the dipstick (usually a yellow/orange handle), wipe it and push it all the way in.",
            "Pull it again: oil should be between MIN and MAX.",
            "If low, add a little of the oil type in your manual through the cap with the oil-can symbol, then re-check.",
            "Very black or thick oil, or a red oil light on the dashboard – get it serviced soon.",
        )),
        "נורת לחץ שמן אדומה בזמן נסיעה – לעצור מיד ולכבות מנוע.", "Red oil-pressure light while driving – stop and switch off immediately."),

    Tip(Icons.Filled.WaterDrop, J.Mint,
        Triple("מים ונוזל קירור", "רדיאטור ומיכל עיבוי", listOf(
            "בודקים רק כשהמנוע קר – בבוקר לפני נסיעה.",
            "מצא את מיכל העיבוי השקוף (מכסה עם סמל מדחום/אזהרה). הנוזל צריך להיות בין MIN ל־MAX.",
            "אם חסר – הוסף נוזל קירור (אנטיפריז) מהסוג המתאים לרכב. בחירום אפשר מים, ולהשלים נוזל בהמשך.",
            "ירידה חוזרת בנוזל או כתמים מתחת לרכב – כנראה נזילה, צריך מוסך.",
            "נוזל לשטיפת שמשות: מיכל נפרד (סמל שמשה). מלא במים עם מעט נוזל ייעודי.",
        )),
        Triple("Water and coolant", "Radiator and expansion tank", listOf(
            "Only check with a cold engine – in the morning before driving.",
            "Find the clear expansion tank (cap with a thermometer/warning symbol). Level between MIN and MAX.",
            "If low, add the coolant (antifreeze) your car needs. In an emergency plain water, then top up properly later.",
            "Repeated drops or puddles under the car mean a leak – see a garage.",
            "Washer fluid is a separate tank (windscreen symbol): water plus a little washer fluid.",
        )),
        "לעולם אל תפתח מכסה רדיאטור כשהמנוע חם – הנוזל רותח ובלחץ.", "Never open the radiator cap on a hot engine – the coolant is boiling and under pressure."),

    Tip(Icons.Filled.Speed, J.Green,
        Triple("לחץ אוויר וצמיגים", "בטיחות וחיסכון בדלק", listOf(
            "בדוק לחץ פעם בשבועיים, כשהצמיגים קרים (לפני נסיעה או אחרי עצירה ארוכה).",
            "הלחץ הנכון כתוב על מדבקה במשקוף דלת הנהג או במכסה הדלק – לא הערך שעל הצמיג עצמו.",
            "לחץ נמוך = יותר בלאי, צריכת דלק גבוהה ואחיזה גרועה יותר.",
            "עומק חריץ: לפי החוק לפחות 1.6 מ״מ. מומלץ להחליף כבר ב־3 מ״מ, במיוחד לקראת החורף.",
            "בליטה, סדק או מסמר בדופן הצמיג – להחלפה, לא לתיקון.",
        )),
        Triple("Tyre pressure and tyres", "Safety and fuel saving", listOf(
            "Check pressure every two weeks with cold tyres (before driving or after a long stop).",
            "The right pressure is on a sticker in the driver's door frame or fuel flap – not the number on the tyre.",
            "Low pressure = more wear, higher fuel use and worse grip.",
            "Tread depth: legal minimum is usually 1.6 mm. Replacing at 3 mm is recommended, especially before winter.",
            "A bulge, crack or nail in the sidewall means replace, not repair.",
        ))),

    Tip(Icons.Filled.Warning, J.Red,
        Triple("נורות אזהרה בלוח", "מה דחוף ומה יכול לחכות", listOf(
            "אדום = לעצור בהקדם: לחץ שמן, טמפרטורת מנוע, בלמים, מצבר/טעינה.",
            "כתום/צהוב = לבדוק בקרוב: נורת מנוע (Check Engine), ABS, לחץ אוויר בצמיגים.",
            "נורת מנוע מהבהבת – להאט ולהגיע למוסך, אפשר להזיק לממיר הקטליטי.",
            "ירוק/כחול = מידע בלבד (אורות, איתות, קרוז).",
            "בספר הרכב יש טבלה עם כל הנורות של הדגם שלך.",
        )),
        Triple("Dashboard warning lights", "What's urgent and what can wait", listOf(
            "Red = stop soon: oil pressure, engine temperature, brakes, battery/charging.",
            "Amber/yellow = check soon: engine light, ABS, tyre pressure.",
            "Flashing engine light – slow down and go to a garage, it can damage the catalytic converter.",
            "Green/blue = information only (lights, indicators, cruise).",
            "Your manual has a table of every light for your model.",
        ))),

    Tip(Icons.Filled.Thermostat, J.Amber,
        Triple("המנוע מתחמם", "מחוג טמפרטורה באדום", listOf(
            "כבה מזגן, והדלק חימום על מקסימום עם מאוורר חזק – זה מוציא חום מהמנוע.",
            "עצור במקום בטוח בהקדם, כבה את המנוע ופתח את מכסה המנוע (בזהירות, אדים).",
            "חכה לפחות 30 דקות עד שהמנוע מתקרר לפני כל בדיקה.",
            "בדוק נוזל קירור במיכל העיבוי והשלם אם חסר. אם המצב חוזר – לא להמשיך לנסוע, לקרוא לגרר.",
        )),
        Triple("Engine overheating", "Temperature gauge in the red", listOf(
            "Switch the A/C off and turn the heater to maximum with a strong fan – it draws heat from the engine.",
            "Stop somewhere safe soon, switch off and open the bonnet (carefully, steam).",
            "Wait at least 30 minutes for the engine to cool before checking anything.",
            "Check the coolant tank and top up if low. If it happens again, don't keep driving – call a tow.",
        ))),

    Tip(Icons.Filled.CarCrash, J.Red,
        Triple("מה עושים בתאונה", "צעדים ראשונים", listOf(
            "עצור, הדלק אורות מצוקה. אם יש נפגעים: מד״א 101, משטרה 100, כבאות 102.",
            "לבש אפוד זוהר, הצב משולש, ועמוד מחוץ לכביש – מאחורי מעקה אם יש.",
            "שמור את סרטון האירוע ב־Jeremy (הוא כבר כולל את השניות שלפני) ונעל אותו.",
            "צלם את הרכבים, הנזק, לוחיות הרישוי והמקום מכמה זוויות.",
            "החלף פרטים: שם, תעודת זהות, טלפון, מספר רכב, חברת ביטוח ומספר פוליסה, ופרטי בעל הרכב אם זה לא הנהג.",
            "קח פרטים של עדים. אל תודה באשמה במקום.",
        )),
        Triple("After an accident", "First steps", listOf(
            "Stop and switch on the hazard lights. If anyone is hurt call the emergency services (in Israel: ambulance 101, police 100, fire 102).",
            "Put on a reflective vest, place the triangle, and stand off the road – behind a barrier if there is one.",
            "Save the event video in Jeremy (it already includes the seconds before) and lock it.",
            "Photograph the cars, the damage, the plates and the scene from several angles.",
            "Exchange details: name, ID, phone, plate, insurer and policy number, and the owner's details if not the driver.",
            "Take witnesses' details. Don't admit fault at the scene.",
        ))),

    Tip(Icons.Filled.Build, J.Mint,
        Triple("ציוד שכדאי שיהיה ברכב", "חובה ומומלץ", listOf(
            "חובה: אפוד זוהר (בתא הנוסעים, לא בתא המטען) ומשולש אזהרה.",
            "גלגל רזרבי תקין עם לחץ אוויר, ג'ק ומפתח ברגים.",
            "כבלי התנעה, פנס, ערכת עזרה ראשונה ומים לשתייה.",
            "מטען לטלפון ומחזיק טלפון יציב – גם בשביל Jeremy.",
            "מטף כיבוי קטן ובקבוק נוזל קירור/מים.",
        )),
        Triple("What to keep in the car", "Required and recommended", listOf(
            "Usually required: a reflective vest (in the cabin, not the boot) and a warning triangle.",
            "A working spare wheel with air in it, a jack and a wheel wrench.",
            "Jump leads, a torch, a first-aid kit and drinking water.",
            "A phone charger and a solid phone mount – for Jeremy too.",
            "A small fire extinguisher and a bottle of coolant/water.",
        ))),

    Tip(Icons.Filled.Umbrella, J.Mint,
        Triple("נהיגה בגשם", "במיוחד בגשם הראשון", listOf(
            "הגשם הראשון אחרי הקיץ מעלה שמן מהכביש – הכביש חלקלק במיוחד.",
            "הכפל את מרחק הביטחון והאט לפני עיקולים.",
            "הדלק אורות נמוכים גם ביום, והפעל מפשיר אדים לשמשות.",
            "החלקה במים (אקוופליינינג): לא לבלום חזק – הורד רגל מהגז והחזק את ההגה ישר.",
            "בדוק מגבים לפני החורף – מגב שמשאיר פסים צריך החלפה.",
        )),
        Triple("Driving in rain", "Especially the first rain", listOf(
            "The first rain after summer lifts oil off the road – it is extra slippery.",
            "Double your following distance and slow down before bends.",
            "Use dipped headlights even by day, and the demister for the windows.",
            "Aquaplaning: don't brake hard – ease off the gas and keep the wheel straight.",
            "Check your wipers before winter – a wiper that leaves streaks needs replacing.",
        ))),

    Tip(Icons.Filled.LocalGasStation, J.Green,
        Triple("חיסכון בדלק", "טיפים קטנים שמצטברים", listOf(
            "נסיעה רגועה: האצה הדרגתית ושימוש בבלימת מנוע חוסכים עד 20%.",
            "לחץ אוויר נכון בצמיגים.",
            "לא להשאיר מנוע עובד בעמידה ממושכת.",
            "להוציא משקל מיותר מתא המטען ולהוריד גגון כשלא צריך.",
            "מזגן בעיר, חלונות סגורים במהירות גבוהה.",
        )),
        Triple("Saving fuel", "Small habits that add up", listOf(
            "Smooth driving: gentle acceleration and engine braking can save up to 20%.",
            "Correct tyre pressure.",
            "Don't idle for long periods.",
            "Remove unneeded weight from the boot and take off a roof box when not in use.",
            "A/C in town, windows closed at high speed.",
        ))),
)

@Composable
fun GuideScreen() {
    var open by remember { mutableIntStateOf(-1) }
    val he = java.util.Locale.getDefault().language.let { it == "iw" || it == "he" }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.guide_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (he) "טיפים לרכב ולדרך – לחץ על נושא כדי לפתוח" else "Car and road tips – tap a topic to open it",
                color = J.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
            tips.forEachIndexed { i, t ->
                val (title, sub, steps) = if (he) t.he else t.en
                val note = if (he) t.tipHe else t.tipEn
                JCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { open = if (open == i) -1 else i }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(t.tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, color = J.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(sub, color = J.TextDim, fontSize = 13.sp)
                            }
                            Icon(if (open == i) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = J.TextDim)
                        }
                        if (open == i) {
                            Spacer(Modifier.height(10.dp))
                            steps.forEachIndexed { n, step ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(t.tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                        Text("${n + 1}", color = t.tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(step, color = J.Text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
                                }
                            }
                            note?.let {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(J.Red.copy(alpha = 0.15f)).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Warning, null, tint = J.Red, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(it, color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                if (he) "המידע כללי. בכל רכב יש הבדלים – ההוראות בספר הרכב קובעות." else "General information – every car differs; your owner's manual comes first.",
                color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
            PermissionsCard()
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Shows (and lets the user fix) the two system settings that most often break background recording. */
@Composable
fun PermissionsCard() {
    val ctx = LocalContext.current
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlay = Settings.canDrawOverlays(ctx); battery = isIgnoringBattery(ctx)
        }
    }
    JCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.sec_perms), color = J.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            PermRow(Icons.Filled.Layers, stringResource(R.string.perm_overlay), overlay) { openOverlaySettings(ctx) }
            PermRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.perm_battery), battery) { requestIgnoreBattery(ctx) }

        }
    }
}

@Composable
private fun PermRow(icon: ImageVector, label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !granted, onClick = onGrant).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = J.Mint)
        Spacer(Modifier.width(12.dp))
        Text(label, color = J.Text, modifier = Modifier.weight(1f))
        if (granted) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = J.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.perm_granted), color = J.Green, fontSize = 13.sp)
        } else TextButton(onGrant) { Text(stringResource(R.string.perm_grant), color = J.Mint) }
    }
}

fun isIgnoringBattery(c: Context): Boolean =
    (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

@SuppressLint("BatteryLife")
fun requestIgnoreBattery(c: Context) {
    runCatching {
        c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
