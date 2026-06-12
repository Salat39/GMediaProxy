package com.geely.online.service

import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import java.util.Locale

class FavoriteRatingController {
    private val rules: List<FavoriteRule> = listOf(
        YandexMusicRule(),
        MurglarRule(),
        VkxRule(),
        DefaultRule()
    )

    fun read(controller: MediaController?): FavoriteStatus? {
        if (controller == null) return null
        return ruleFor(controller.packageName).read(controller)
    }

    fun apply(controller: MediaController?, forcedLike: Boolean?): FavoriteApplyResult {
        if (controller == null) return FavoriteApplyResult(forcedLike ?: false, false)
        val rule = ruleFor(controller.packageName)
        val current = rule.read(controller)
        val wantLike = forcedLike ?: !(current?.liked ?: false)
        val handled = rule.apply(controller, wantLike)
        return FavoriteApplyResult(wantLike, handled)
    }

    fun needsTrackChangeSettle(packageName: String): Boolean {
        return packageName == AuxiliaryPackages.MURGLAR
    }

    private fun ruleFor(packageName: String): FavoriteRule {
        return rules.firstOrNull { it.supports(packageName) } ?: DefaultRule()
    }

    data class FavoriteStatus(
        val liked: Boolean,
        val known: Boolean
    )

    data class FavoriteApplyResult(
        val wantedLike: Boolean,
        val handled: Boolean
    )

    private interface FavoriteRule {
        fun supports(packageName: String): Boolean
        fun read(controller: MediaController): FavoriteStatus?
        fun apply(controller: MediaController, wantLike: Boolean): Boolean
    }

    private class YandexMusicRule : FavoriteRule {
        override fun supports(packageName: String): Boolean = packageName == AuxiliaryPackages.YANDEX_MUSIC

        override fun read(controller: MediaController): FavoriteStatus? {
            return readUserRating(controller.metadata)
        }

        override fun apply(controller: MediaController, wantLike: Boolean): Boolean {
            return runCatching {
                controller.transportControls.setRating(Rating.newHeartRating(wantLike))
                true
            }.getOrDefault(false)
        }
    }

    private class MurglarRule : FavoriteRule {
        override fun supports(packageName: String): Boolean = packageName == AuxiliaryPackages.MURGLAR

        override fun read(controller: MediaController): FavoriteStatus? {
            val actions = controller.playbackState?.customActions.orEmpty()
            val hasLikeAction = actions.any { isLikeAction(it.action) }
            val hasDislikeAction = actions.any { isDislikeAction(it.action) }
            return when {
                hasLikeAction && hasDislikeAction -> FavoriteStatus(false, false)
                hasDislikeAction -> FavoriteStatus(true, true)
                hasLikeAction -> FavoriteStatus(false, true)
                else -> null
            }
        }

        override fun apply(controller: MediaController, wantLike: Boolean): Boolean {
            val action = controller.playbackState?.customActions.orEmpty().firstOrNull {
                if (wantLike) isLikeAction(it.action) else isDislikeAction(it.action)
            }?.action ?: return false
            return sendCustomAction(controller, action)
        }

        private companion object {
            fun isLikeAction(action: String?): Boolean {
                val value = normalize(action)
                return value == "action_like" || value.endsWith(".action_like") || value.endsWith(":action_like")
            }

            fun isDislikeAction(action: String?): Boolean {
                val value = normalize(action)
                return value == "action_dislike" || value.endsWith(".action_dislike") || value.endsWith(":action_dislike")
            }
        }
    }

    private class VkxRule : FavoriteRule {
        override fun supports(packageName: String): Boolean = packageName == AuxiliaryPackages.VKX

        override fun read(controller: MediaController): FavoriteStatus? {
            val actions = controller.playbackState?.customActions.orEmpty()
            if (actions.any { isRemoveFromLibrary(it.name?.toString()) }) {
                return FavoriteStatus(true, true)
            }
            if (actions.any { isAddToLibrary(it.name?.toString()) }) {
                return FavoriteStatus(false, true)
            }
            return null
        }

        override fun apply(controller: MediaController, wantLike: Boolean): Boolean {
            val action = controller.playbackState?.customActions.orEmpty().firstOrNull {
                val name = it.name?.toString()
                if (wantLike) isAddToLibrary(name) else isRemoveFromLibrary(name)
            }?.action ?: return false
            return sendCustomAction(controller, action)
        }

        private companion object {
            fun isAddToLibrary(name: String?): Boolean {
                val value = normalize(name)
                return value.contains("добавить") && value.contains("библиот")
            }

            fun isRemoveFromLibrary(name: String?): Boolean {
                val value = normalize(name)
                return value.contains("удалить") && value.contains("библиот")
            }
        }
    }

    private class DefaultRule : FavoriteRule {
        override fun supports(packageName: String): Boolean = true

        override fun read(controller: MediaController): FavoriteStatus? {
            readUserRating(controller.metadata)?.let { return it }
            return readCustomActions(controller.playbackState)
        }

        override fun apply(controller: MediaController, wantLike: Boolean): Boolean {
            if (applyUserRating(controller, wantLike)) {
                return true
            }
            val action = findCustomAction(controller.playbackState, wantLike)?.action ?: return false
            return sendCustomAction(controller, action)
        }

        private fun readCustomActions(playbackState: PlaybackState?): FavoriteStatus? {
            val actions = playbackState?.customActions.orEmpty()
            if (actions.any { matchesDislike(it) }) {
                return FavoriteStatus(true, true)
            }
            if (actions.any { matchesLike(it) }) {
                return FavoriteStatus(false, true)
            }
            return null
        }

        private fun findCustomAction(playbackState: PlaybackState?, wantLike: Boolean): PlaybackState.CustomAction? {
            return playbackState?.customActions.orEmpty().firstOrNull {
                if (wantLike) matchesLike(it) else matchesDislike(it)
            }
        }

        private fun matchesLike(action: PlaybackState.CustomAction): Boolean {
            val value = searchText(action)
            return LIKE_WORDS.any { value.contains(it, true) } && DISLIKE_WORDS.none { value.contains(it, true) }
        }

        private fun matchesDislike(action: PlaybackState.CustomAction): Boolean {
            val value = searchText(action)
            return DISLIKE_WORDS.any { value.contains(it, true) }
        }

        private companion object {
            val LIKE_WORDS = listOf(
                "like",
                "heart",
                "favorite",
                "favourite",
                "favor",
                "нравится",
                "лайк",
                "избран",
                "любим",
                "добавить"
            )

            val DISLIKE_WORDS = listOf(
                "dislike",
                "unlike",
                "remove",
                "delete",
                "удалить",
                "убрать",
                "дизлайк"
            )
        }
    }
}

private fun readUserRating(metadata: MediaMetadata?): FavoriteRatingController.FavoriteStatus? {
    val rating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return null
    return when (rating.ratingStyle) {
        Rating.RATING_HEART -> FavoriteRatingController.FavoriteStatus(rating.hasHeart(), true)
        Rating.RATING_THUMB_UP_DOWN -> FavoriteRatingController.FavoriteStatus(rating.isThumbUp, true)
        Rating.RATING_5_STARS -> FavoriteRatingController.FavoriteStatus(rating.starRating >= 4.0f, true)
        else -> null
    }
}

private fun applyUserRating(controller: MediaController, wantLike: Boolean): Boolean {
    val ratingType = controller.ratingType
    val rating = when (ratingType) {
        Rating.RATING_HEART -> Rating.newHeartRating(wantLike)
        Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(wantLike)
        Rating.RATING_5_STARS -> Rating.newStarRating(Rating.RATING_5_STARS, if (wantLike) 5.0f else 0.0f)
        else -> null
    } ?: return false
    return runCatching {
        controller.transportControls.setRating(rating)
        true
    }.getOrDefault(false)
}

private fun sendCustomAction(controller: MediaController, action: String) = runCatching {
    controller.transportControls.sendCustomAction(action, null)
    true
}.getOrDefault(false)

private fun searchText(action: PlaybackState.CustomAction) =
    listOfNotNull(action.action, action.name?.toString()).joinToString(" ").let(::normalize)

private fun normalize(value: String?) = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
