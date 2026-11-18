package com.carcast.mirror.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carcast.mirror.R
import com.carcast.mirror.ui.theme.ElectricCyan
import com.carcast.mirror.ui.theme.NeonGreen
import com.carcast.mirror.ui.theme.ObsidianBg
import com.carcast.mirror.ui.theme.TextPrimary
import com.carcast.mirror.ui.theme.TextSecondary

@Composable
fun PaymentDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF090D16),
                            Color(0xFF020617)
                        )
                    )
                )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val isLandscape = maxWidth > maxHeight

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // --- 1. Top Bar: Heading + Subtitle + Close Button ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFFFF6D00), Color(0xFF00C853))
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Scan to Pay",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = TextPrimary,
                                        letterSpacing = 0.5.sp
                                    )
                                )
                                Text(
                                    text = "Muhammad Mustajab Haider",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }

                        // Close (X) Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF1E293B), CircleShape)
                                .border(1.dp, Color(0xFF334155), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Payment Screen",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // --- 2. Main Payment QR Cards (Landscape Row vs Portrait Column) ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLandscape) {
                            // Landscape Car LCD / Landscape Phone Layout: Side-by-Side
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: NayaPay Card
                                PaymentMethodCard(
                                    title = "NayaPay",
                                    subtitle = "mustajab.512@nayapay",
                                    accentColor = Color(0xFFFF5722),
                                    qrDrawableId = R.drawable.qr_nayapay,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )

                                // Right: Easypaisa Card
                                PaymentMethodCard(
                                    title = "easypaisa",
                                    subtitle = "MUHAMMAD MUSTAJAB HAIDER",
                                    accentColor = Color(0xFF00A859),
                                    qrDrawableId = R.drawable.qr_easypaisa,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        } else {
                            // Portrait Phone Layout: Vertical Stack with smooth scroll
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                PaymentMethodCard(
                                    title = "NayaPay",
                                    subtitle = "mustajab.512@nayapay",
                                    accentColor = Color(0xFFFF5722),
                                    qrDrawableId = R.drawable.qr_nayapay,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(290.dp)
                                )

                                PaymentMethodCard(
                                    title = "easypaisa",
                                    subtitle = "MUHAMMAD MUSTAJAB HAIDER",
                                    accentColor = Color(0xFF00A859),
                                    qrDrawableId = R.drawable.qr_easypaisa,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(290.dp)
                                )
                            }
                        }
                    }

                    // --- 3. Footer Instruction ---
                    Text(
                        text = "Scan with NayaPay, Easypaisa, Raast, or any Pakistan Banking App",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    qrDrawableId: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0D121D))
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(
                        accentColor.copy(alpha = 0.9f),
                        accentColor.copy(alpha = 0.3f),
                        Color(0xFF1E293B)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Logo / Service Name Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = accentColor,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                )
            }

            // QR Code Container (Square, crisp white background with quiet zone)
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp), ambientColor = accentColor, spotColor = accentColor)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = qrDrawableId),
                    contentDescription = "$title Payment QR Code",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Account identifier footer
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161E2E))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
