/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.mms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.makeramen.RoundedDrawable;
import com.nineoldandroids.animation.ObjectAnimator;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.LRUCache;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.MediaUtil.ThumbnailData;
import org.thoughtcrime.securesms.util.SmilUtil;
import org.thoughtcrime.securesms.util.Util;
import org.w3c.dom.smil.SMILDocument;
import org.w3c.dom.smil.SMILMediaElement;
import org.w3c.dom.smil.SMILRegionElement;
import org.thoughtcrime.securesms.crypto.MasterSecret;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.PduPart;

public class ImageSlide extends Slide {
  private static final String TAG = ImageSlide.class.getSimpleName();

  private ObjectAnimator mediaFadeAnimator;

  private static final int MAX_CACHE_SIZE = 10;
  private static final Map<Uri, SoftReference<Drawable>> thumbnailCache =
      Collections.synchronizedMap(new LRUCache<Uri, SoftReference<Drawable>>(MAX_CACHE_SIZE));

  public ImageSlide(Context context, MasterSecret masterSecret, PduPart part) {
    super(context, masterSecret, part);
  }

  public ImageSlide(Context context, Uri uri) throws IOException, BitmapDecodingException {
    super(context, constructPartFromUri(uri));
  }

  @Override
  public Drawable getThumbnail(Context context, int maxWidth, int maxHeight) {
    Drawable thumbnail = getCachedThumbnail();

    if (thumbnail != null) {
      return thumbnail;
    }

    try {
      Bitmap thumbnailBitmap;
      long startDecode = System.currentTimeMillis();

      if (part.getDataUri() != null && part.getId() > -1) {
        thumbnailBitmap = BitmapFactory.decodeStream(DatabaseFactory.getPartDatabase(context)
                                                                    .getThumbnailStream(masterSecret, part.getId()));
      } else if (part.getDataUri() != null) {
        Log.w(TAG, "generating thumbnail for new part");
        ThumbnailData thumbnailData = MediaUtil.generateThumbnail(context, masterSecret,
                                                                  part.getDataUri(), Util.toIsoString(part.getContentType()));
        thumbnailBitmap = thumbnailData.getBitmap();
        part.setThumbnail(thumbnailBitmap);
      } else {
        throw new FileNotFoundException("no data location specified");
      }

      Log.w(TAG, "thumbnail decode/generate time: " + (System.currentTimeMillis() - startDecode) + "ms");

      thumbnail = new BitmapDrawable(context.getResources(), thumbnailBitmap);
      thumbnailCache.put(part.getDataUri(), new SoftReference<>(thumbnail));

      return thumbnail;
    } catch (IOException | BitmapDecodingException e) {
      Log.w(TAG, e);
      return context.getResources().getDrawable(R.drawable.ic_missing_thumbnail_picture);
    }
  }

  @Override
  public void setThumbnailOn(Context context, ImageView imageView, View imageContainer) {
    setThumbnailOn(context, imageView, imageContainer, imageView.getWidth(), imageView.getHeight(), new ColorDrawable(Color.TRANSPARENT));
  }

  @Override
  public void setThumbnailOn(Context context, ImageView imageView, View imageContainer, final int width, final int height, final Drawable placeholder) {
    Drawable thumbnail = getCachedThumbnail();

    if (thumbnail != null) {
      Log.w("ImageSlide", "Setting cached thumbnail...");
      setThumbnailOn(imageView, imageContainer, thumbnail, true);
      return;
    }

    final WeakReference<Context>   weakContext   = new WeakReference<>(context);
    final Drawable temporaryDrawable             = RoundedDrawable.fromDrawable(placeholder);
    final WeakReference<ImageView> weakImageView = new WeakReference<>(imageView);
    final WeakReference<View> weakContainer      = new WeakReference<>(imageContainer);
    final Handler handler                        = new Handler();

    imageView.setImageDrawable(temporaryDrawable);

    if (width == 0 || height == 0)
      return;

    MmsDatabase.slideResolver.execute(new Runnable() {
      @Override
      public void run() {
        final Context context = weakContext.get();
        if (context == null) {
          Log.w(TAG, "context SoftReference was null, leaving");
          return;
        }

        final Drawable bitmap = getThumbnail(context, width, height);
        final ImageView destination = weakImageView.get();
        final View      container   = weakContainer.get();

        Log.w(TAG, "slide resolved, destination available? " + (destination == null));
        if (destination != null && container != null && destination.getDrawable() == temporaryDrawable) {
          handler.post(new Runnable() {
            @Override
            public void run() {
              setThumbnailOn(destination, container, bitmap, false);
            }
          });
        }
      }
    });
  }

  private void setThumbnailOn(ImageView imageView, View imageContainer, Drawable thumbnail, boolean fromMemory) {
    if (thumbnail.getIntrinsicWidth() < imageView.getWidth() &&
        thumbnail.getIntrinsicHeight() < imageView.getHeight()) {
      imageView.setScaleType(ScaleType.CENTER_INSIDE);
    } else {
      imageView.setScaleType(ScaleType.CENTER_CROP);
    }
    imageView.setImageDrawable(thumbnail);

    if (!fromMemory && VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
      imageContainer.setAlpha(0f);
      imageContainer.animate().alpha(1f).setDuration(300);
    } else if (!fromMemory) {
      AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
      alpha.setDuration(300);
      alpha.setFillAfter(true);
      imageContainer.startAnimation(alpha);
    }
    imageContainer.setVisibility(View.VISIBLE);
  }

  private Drawable getCachedThumbnail() {
    synchronized (thumbnailCache) {
      SoftReference<Drawable> bitmapReference = thumbnailCache.get(part.getDataUri());
      Log.w("ImageSlide", "Got soft reference: " + bitmapReference);

      if (bitmapReference != null) {
        Drawable bitmap = bitmapReference.get();
        Log.w("ImageSlide", "Got cached bitmap: " + bitmap);
        if (bitmap != null) return bitmap;
        else                thumbnailCache.remove(part.getDataUri());
      }
    }

    return null;
  }

  @Override
  public boolean hasImage() {
    return true;
  }

  @Override
  public SMILRegionElement getSmilRegion(SMILDocument document) {
    SMILRegionElement region = (SMILRegionElement) document.createElement("region");
    region.setId("Image");
    region.setLeft(0);
    region.setTop(0);
    region.setWidth(SmilUtil.ROOT_WIDTH);
    region.setHeight(SmilUtil.ROOT_HEIGHT);
    region.setFit("meet");
    return region;
  }

  @Override
  public SMILMediaElement getMediaElement(SMILDocument document) {
    return SmilUtil.createMediaElement("img", document, new String(getPart().getName()));
  }

  private static PduPart constructPartFromUri(Uri uri)
      throws IOException, BitmapDecodingException
  {
    PduPart part = new PduPart();

    part.setDataUri(uri);
    part.setContentType(ContentType.IMAGE_JPEG.getBytes());
    part.setContentId((System.currentTimeMillis()+"").getBytes());
    part.setName(("Image" + System.currentTimeMillis()).getBytes());

    return part;
  }
}
