private fun openVideo(item: VideoItem) {
    val visibleVideos = adapter.current
    AlertDialog.Builder(this)
        .setTitle("Pilih cara memutar")
        .setItems(arrayOf("Putar semua video", "Putar video ini saja")) { _, which ->
            Playlist.items = if (which == 0) visibleVideos else listOf(item)
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", item.id))
        }
        .show()
}
