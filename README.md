# Sample ARCore

Try to place an object and save its position in the scene by using geospatial cloud anchor

## How it work

A super simple example to test ARCore Geospatial.
We're going to create a geospatial cloud anchor and save it by hostCloudAnchor method.
Before to run the app, you need to create your own ARCore api key following [this guide](https://developers.google.com/ar/develop/c/cloud-anchors/developer-guide)

### Known issue

1. Saved object is not in the same position after resolve
   Repro steps:

   1. Open the app
   2. After AR is started and some plane are visible, tap on the screen to place an object
   3. tap on "Save" button
   4. the anchor is saved, but if we use the anchor object from the ARCore api callback, it has a wrong position

   You can search for a FIXME in the code in order to begin to debug that flow

### Some dependency / configuration notes

- Using gradle 7.4 instead of 7.3.3 breaks the plane recognition, and the app is unusable

### Troubleshooting

- if session tracking state is STOPPED, look at the logged error, and follow this [api page](https://developers.google.com/ar/reference/java/com/google/ar/core/Earth.EarthState)
  in case you have a auth error, follow [these steps](https://developers.google.com/ar/develop/c/cloud-anchors/developer-guide)

### Contributors

We appreciate your contribution to resolve this magic not working example.
And we'll offer you a beer for sure if you can resolve this not working example in some way - but without hacking it :-)
