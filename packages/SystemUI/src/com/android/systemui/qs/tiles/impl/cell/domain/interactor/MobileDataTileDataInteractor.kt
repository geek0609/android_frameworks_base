/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles.impl.cell.domain.interactor

import android.annotation.StringRes
import android.content.Context
import android.os.UserHandle
import android.text.Html
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileIcon
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

class MobileDataTileDataInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val mobileIconsInteractor: MobileIconsInteractor,
    private val mobileContextProvider: MobileContextProvider,
    private val qsPipelineFlagsRepository: QSPipelineFlagsRepository,
) : QSTileDataInteractor<MobileDataTileModel> {
    private val mobileDataLabel: String =
        context.getString(R.string.quick_settings_cellular_detail_title)

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<MobileDataTileModel> = tileData()

    fun tileData(): Flow<MobileDataTileModel> =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest {
            if (it == null) {
                    flowOf(
                        MobileDataTileModel(
                            isSimActive = false,
                            isEnabled = false,
                            icon =
                                MobileDataTileIcon.ResourceIcon(
                                    Icon.Resource(
                                        com.android.settingslib.R.drawable.ic_mobile_4_4_bar,
                                        ContentDescription.Loaded(mobileDataLabel),
                                    )
                                ),
                        ),
                    )
            } else {
                combine(
                    it.isDataEnabled,
                    it.signalLevelIcon,
                    mobileSecondaryLabel(it),
                ) { isDataEnabled, signalLevelIcon, secondaryLabel ->
                    val icon =
                        if (isDataEnabled) {
                            when (signalLevelIcon) {
                                is SignalIconModel.Cellular -> {
                                    val signalState =
                                        SignalDrawable.getState(
                                            signalLevelIcon.level,
                                            signalLevelIcon.numberOfLevels,
                                            signalLevelIcon.showExclamationMark,
                                        )
                                    MobileDataTileIcon.SignalIcon(signalState)
                                }

                                is SignalIconModel.Satellite -> {
                                    MobileDataTileIcon.ResourceIcon(
                                        Icon.Resource(
                                            signalLevelIcon.icon.resId,
                                            signalLevelIcon.icon.contentDescription,
                                        )
                                    )
                                }
                            }
                        } else {
                            MobileDataTileIcon.ResourceIcon(
                                Icon.Resource(
                                    R.drawable.ic_signal_mobile_data_off,
                                    ContentDescription.Loaded(mobileDataLabel),
                                )
                            )
                        }
                    MobileDataTileModel(
                        isSimActive = true,
                        isEnabled = isDataEnabled,
                        icon = icon,
                        secondaryLabel = secondaryLabel,
                    )
                }
            }
            .onStart {
                MobileDataTileModel(
                    isSimActive = false,
                    isEnabled = false,
                    icon =
                        MobileDataTileIcon.ResourceIcon(
                            Icon.Resource(
                                R.drawable.ic_signal_mobile_data_off,
                                ContentDescription.Loaded(mobileDataLabel),
                            )
                        ),
                )
            }
        }

    private fun mobileSecondaryLabel(interactor: MobileIconInteractor): Flow<CharSequence?> =
        combine(
            interactor.networkName,
            interactor.signalLevelIcon,
            interactor.isDataConnected,
            mobileDataContentName(interactor),
        ) { networkNameModel, signalIcon, isDataConnected, dataContentDescription ->
            when (signalIcon) {
                is SignalIconModel.Cellular ->
                    mobileDataContentConcat(
                        networkNameModel.name,
                        if (isDataConnected) dataContentDescription else null,
                    )
                        ?.takeIf { it.isNotBlank() }
                is SignalIconModel.Satellite ->
                    signalIcon.icon.contentDescription
                        ?.loadContentDescription(context)
                        ?.takeIf { it.isNotBlank() }
            }
        }

    private fun mobileDataContentName(interactor: MobileIconInteractor): Flow<CharSequence?> =
        combine(interactor.isRoaming, interactor.networkTypeIconGroup) { isRoaming, networkType ->
            val mobileContext =
                mobileContextProvider.getMobileContextForSub(interactor.subscriptionId, context)
            val networkTypeDescription = loadString(networkType.contentDescription, mobileContext)
            if (isRoaming) {
                val roaming = mobileContext.getString(R.string.data_connection_roaming)
                if (networkTypeDescription != null) {
                    mobileContext.getString(
                        R.string.mobile_data_text_format,
                        roaming,
                        networkTypeDescription,
                    )
                } else {
                    roaming
                }
            } else {
                networkTypeDescription
            }
        }

    private fun mobileDataContentConcat(
        networkName: String?,
        dataContentDescription: CharSequence?,
    ): CharSequence? {
        if (dataContentDescription == null) {
            return networkName
        }
        if (networkName == null) {
            return Html.fromHtml(dataContentDescription.toString(), 0)
        }

        return Html.fromHtml(
            context.getString(
                R.string.mobile_carrier_text_format,
                networkName,
                dataContentDescription,
            ),
            0,
        )
    }

    private fun loadString(@StringRes resId: Int, context: Context): CharSequence? =
        if (resId != 0) {
            context.getString(resId)
        } else {
            null
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(isAvailable())

    fun isAvailable(): Boolean {
        return AconfigFlags.qsSplitInternetTile()
    }
}
