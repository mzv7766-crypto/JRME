package com.shomer.bodycam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shomer.bodycam.R
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.ui.components.PrimaryButton
import com.shomer.bodycam.ui.components.ShomerMark
import com.shomer.bodycam.ui.theme.S

/** First run: what a body camera does differently (2 pages), then the name/badge stamped into every video. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var id by rememberSaveable { mutableStateOf(SettingsStore.current.operatorId) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= page) S.Amber else S.Stroke)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            when (page) {
                0 -> {
                    ShomerMark(110.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.ob1_title), color = S.Text, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.ob1_body), color = S.TextDim, fontSize = 16.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(20.dp))
                    Point(Icons.Filled.Shield, R.string.ob_p1)
                    Point(Icons.Filled.VerifiedUser, R.string.ob_p2)
                }
                1 -> {
                    Text(stringResource(R.string.ob2_title), color = S.Text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(16.dp))
                    Point(Icons.Filled.VolumeUp, R.string.ob_p3)
                    Point(Icons.Filled.DarkMode, R.string.ob_p4)
                    Point(Icons.Filled.Vibration, R.string.ob_p5)
                    Point(Icons.Filled.PersonOff, R.string.ob_p6)
                }
                else -> {
                    Text(stringResource(R.string.ob3_title), color = S.Text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ob3_body), color = S.TextDim, fontSize = 15.sp, lineHeight = 21.sp)
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        id, { id = it.take(32) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.operator_id_hint), color = S.TextDim) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = S.Amber, cursorColor = S.Amber),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.ob3_preview), color = S.TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(S.BgDeep).padding(10.dp)) {
                        Text("SHOMER" + id.trim().takeIf { it.isNotEmpty() }?.let { " • $it" }.orEmpty(), color = S.Amber, fontFamily = S.Mono, fontWeight = FontWeight.Bold)
                        Text("2026-10-11  09:41:07  +0300", color = S.Text, fontFamily = S.Mono, fontSize = 13.sp)
                        Text("31.77612, 35.21371  ±6m", color = S.Text, fontFamily = S.Mono, fontSize = 13.sp)
                    }
                }
            }
        }
        PrimaryButton(
            stringResource(if (page < 2) R.string.next else R.string.ob_done), if (page < 2) Icons.AutoMirrored.Filled.ArrowForward else null,
            Modifier.fillMaxWidth().height(60.dp),
        ) {
            if (page < 2) page++ else { SettingsStore.update { it.copy(operatorId = id.trim()) }; onDone() }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(S.AmberDeep), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = S.Amber, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), color = S.Text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}
