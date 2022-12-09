package com.example.arsample.helpers

import com.google.ar.core.Anchor

class SceneManager {

    private var anchors: ArrayList<Anchor> = ArrayList()

    fun init() {
        this.anchors.clear()
    }

    fun addAnchor(anchor: Anchor) {
        this.anchors.add(anchor)
    }

    fun getAllAnchors(): ArrayList<Anchor> {
        return this.anchors
    }

}