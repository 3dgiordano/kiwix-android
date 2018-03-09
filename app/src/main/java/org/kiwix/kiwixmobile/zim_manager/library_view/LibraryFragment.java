/*
 * Kiwix Android
 * Copyright (C) 2018  Kiwix <android.kiwix.org>
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.kiwix.kiwixmobile.zim_manager.library_view;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.kiwix.kiwixmobile.KiwixApplication;
import org.kiwix.kiwixmobile.KiwixMobileActivity;
import org.kiwix.kiwixmobile.R;
import org.kiwix.kiwixmobile.downloader.DownloadFragment;
import org.kiwix.kiwixmobile.downloader.DownloadIntent;
import org.kiwix.kiwixmobile.downloader.DownloadService;
import org.kiwix.kiwixmobile.library.LibraryRecyclerViewAdapter;
import org.kiwix.kiwixmobile.library.contract.ILibraryItemClickListener;
import org.kiwix.kiwixmobile.network.KiwixService;
import org.kiwix.kiwixmobile.utils.NetworkUtils;
import org.kiwix.kiwixmobile.utils.SharedPreferenceUtil;
import org.kiwix.kiwixmobile.utils.StorageUtils;
import org.kiwix.kiwixmobile.utils.StyleUtils;
import org.kiwix.kiwixmobile.utils.TestingUtils;
import org.kiwix.kiwixmobile.zim_manager.ZimManageActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import eu.mhutti1.utils.storage.StorageDevice;
import eu.mhutti1.utils.storage.support.StorageSelectDialog;

import static android.view.View.GONE;
import static org.kiwix.kiwixmobile.downloader.DownloadService.KIWIX_ROOT;
import static org.kiwix.kiwixmobile.library.entity.LibraryNetworkEntity.Book;
import static org.kiwix.kiwixmobile.utils.Constants.EXTRA_BOOK;



public class LibraryFragment extends Fragment
    implements ILibraryItemClickListener, StorageSelectDialog.OnSelectListener, LibraryViewCallback {


  @BindView(R.id.library_list)
  RecyclerView libraryRecyclerView;
  @BindView(R.id.network_permission_text)
  TextView networkText;
  @BindView(R.id.network_permission_button)
  Button permissionButton;

  @Inject
  KiwixService kiwixService;

  public LinearLayout llLayout;

  @BindView(R.id.library_swiperefresh)
  SwipeRefreshLayout swipeRefreshLayout;

  private ArrayList<Book> books = new ArrayList<>();

  public static DownloadService mService = new DownloadService();

  private boolean mBound;

  public LibraryRecyclerViewAdapter libraryRecyclerViewAdapter;

  private DownloadServiceConnection mConnection = new DownloadServiceConnection();

  @Inject
  ConnectivityManager conMan;

  private ZimManageActivity faActivity;

  public static NetworkBroadcastReceiver networkBroadcastReceiver;

  public static List<Book> downloadingBooks = new ArrayList<>();

  public static boolean isReceiverRegistered = false;

  @Inject
  LibraryPresenter presenter;

  @Inject
  SharedPreferenceUtil sharedPreferenceUtil;

  private void setupDagger() {
    KiwixApplication.getInstance().getApplicationComponent().inject(this);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {

    setupDagger();
    TestingUtils.bindResource(LibraryFragment.class);
    llLayout = (LinearLayout) inflater.inflate(R.layout.activity_library, container, false);
    ButterKnife.bind(this, llLayout);
    ViewCompat.setNestedScrollingEnabled(libraryRecyclerView, false);
    presenter.attachView(this);

    networkText = llLayout.findViewById(R.id.network_text);

    faActivity = (ZimManageActivity) super.getActivity();
    swipeRefreshLayout.setOnRefreshListener(() -> refreshFragment());
    setupLibraryRecyclerView();

    DownloadService.setDownloadFragment(faActivity.mSectionsPagerAdapter.getDownloadFragment());

    NetworkInfo network = conMan.getActiveNetworkInfo();
    if (network == null || !network.isConnected()) {
      displayNoNetworkConnection();
    }

    networkBroadcastReceiver = new NetworkBroadcastReceiver();
    faActivity.registerReceiver(networkBroadcastReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    isReceiverRegistered = true;

    presenter.loadRunningDownloadsFromDb(getActivity());
    return llLayout;
  }

  private void setupLibraryRecyclerView() {
    libraryRecyclerViewAdapter = new LibraryRecyclerViewAdapter(super.getContext(), this);
    libraryRecyclerView.setLayoutManager(new LinearLayoutManager(super.getContext()));
    libraryRecyclerView.setAdapter(libraryRecyclerViewAdapter);
  }


  @Override
  public void showBooks(LinkedList<Book> books) {
    if (books == null) {
      displayNoItemsAvailable();
      return;
    }

    Log.i("kiwix-showBooks", "Contains:" + books.size());
    libraryRecyclerViewAdapter.setAllBooks(books);
    if (faActivity.searchView != null) {
      libraryRecyclerViewAdapter.getFilter().filter(
          faActivity.searchView.getQuery(),
          i -> stopScanningContent());
    } else {
      libraryRecyclerViewAdapter.getFilter().filter("", i -> stopScanningContent());
    }
    libraryRecyclerViewAdapter.notifyDataSetChanged();
  }

  @Override
  public void displayNoNetworkConnection() {
    if (books.size() != 0) {
      Toast.makeText(super.getActivity(), R.string.no_network_connection, Toast.LENGTH_LONG).show();
      return;
    }

    networkText.setText(R.string.no_network_connection);
    networkText.setVisibility(View.VISIBLE);
    permissionButton.setVisibility(GONE);
    swipeRefreshLayout.setRefreshing(false);
    swipeRefreshLayout.setEnabled(false);
    libraryRecyclerView.setVisibility(View.INVISIBLE);
    TestingUtils.unbindResource(LibraryFragment.class);
  }

  @Override
  public void displayNoItemsFound() {
    networkText.setText(R.string.no_items_msg);
    networkText.setVisibility(View.VISIBLE);
    permissionButton.setVisibility(GONE);
    swipeRefreshLayout.setRefreshing(false);
    TestingUtils.unbindResource(LibraryFragment.class);
  }

  @Override
  public void displayNoItemsAvailable() {
    if (books.size() != 0) {
      Toast.makeText(super.getActivity(), R.string.no_items_available, Toast.LENGTH_LONG).show();
      return;
    }

    networkText.setText(R.string.no_items_available);
    networkText.setVisibility(View.VISIBLE);
    permissionButton.setVisibility(View.GONE);
    swipeRefreshLayout.setRefreshing(false);
    TestingUtils.unbindResource(LibraryFragment.class);
  }

  @Override
  public void displayScanningContent() {
    if (!swipeRefreshLayout.isRefreshing()) {
      networkText.setVisibility(GONE);
      permissionButton.setVisibility(GONE);
      swipeRefreshLayout.setEnabled(true);
      swipeRefreshLayout.setRefreshing(true);
      TestingUtils.bindResource(LibraryFragment.class);
    }
  }


  @Override
  public void stopScanningContent() {
    networkText.setVisibility(GONE);
    permissionButton.setVisibility(GONE);
    swipeRefreshLayout.setRefreshing(false);
    TestingUtils.unbindResource(LibraryFragment.class);
  }

  public void refreshFragment() {
    NetworkInfo network = conMan.getActiveNetworkInfo();
    if (network == null || !network.isConnected()) {
      Toast.makeText(super.getActivity(), R.string.no_network_connection, Toast.LENGTH_LONG).show();
      swipeRefreshLayout.setRefreshing(false);
      return;
    }
    networkBroadcastReceiver.onReceive(super.getActivity(), null);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (mBound && super.getActivity() != null) {
      super.getActivity().unbindService(mConnection.downloadServiceInterface);
      mBound = false;
    }
  }

  @Override
  public void onLibraryItemClick(int position) {
    if (!libraryRecyclerViewAdapter.isDivider(position)) {
      if (getSpaceAvailable()
          < Long.parseLong(((Book) (libraryRecyclerViewAdapter.getListItems().get(position).data)).getSize()) * 1024f) {
        Toast.makeText(super.getActivity(), getString(R.string.download_no_space)
            + "\n" + getString(R.string.space_available) + " "
            + LibraryUtils.bytesToHuman(getSpaceAvailable()), Toast.LENGTH_LONG).show();
        Snackbar snackbar = Snackbar.make(libraryRecyclerView,
            getString(R.string.download_change_storage),
            Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.open), v -> {
              FragmentManager fm = getFragmentManager();
              StorageSelectDialog dialogFragment = new StorageSelectDialog();
              Bundle b = new Bundle();
              b.putString(StorageSelectDialog.STORAGE_DIALOG_INTERNAL, getResources().getString(R.string.internal_storage));
              b.putString(StorageSelectDialog.STORAGE_DIALOG_EXTERNAL, getResources().getString(R.string.external_storage));
              b.putInt(StorageSelectDialog.STORAGE_DIALOG_THEME, StyleUtils.dialogStyle());
              dialogFragment.setArguments(b);
              dialogFragment.setOnSelectListener(this);
              dialogFragment.show(fm, getResources().getString(R.string.pref_storage));
            });
        snackbar.setActionTextColor(Color.WHITE);
        snackbar.show();
        return;
      }

      if (DownloadFragment.mDownloadFiles
          .containsValue(KIWIX_ROOT + StorageUtils.getFileNameFromUrl(((Book) libraryRecyclerViewAdapter
              .getListItems().get(position).data).getUrl()))) {
        Toast.makeText(super.getActivity(), getString(R.string.zim_already_downloading), Toast.LENGTH_LONG)
            .show();
      } else {

        NetworkInfo network = conMan.getActiveNetworkInfo();
        if (network == null || !network.isConnected()) {
          Toast.makeText(super.getActivity(), getString(R.string.no_network_connection), Toast.LENGTH_LONG)
              .show();
          return;
        }

        if (KiwixMobileActivity.wifiOnly && !NetworkUtils.isWiFi(getContext())) {
          DownloadFragment.showNoWiFiWarning(getContext(), () -> {
            downloadFile((Book) libraryRecyclerViewAdapter.getListItems().get(position).data);
          });
        } else {
          downloadFile((Book) libraryRecyclerViewAdapter.getListItems().get(position).data);
        }
      }
    }
  }

  @Override
  public void downloadFile(Book book) {
    downloadingBooks.add(book);
    if (libraryRecyclerViewAdapter != null && faActivity != null && faActivity.searchView != null) {
      libraryRecyclerViewAdapter.getFilter().filter(faActivity.searchView.getQuery());
    }
    Toast.makeText(super.getActivity(), getString(R.string.download_started_library), Toast.LENGTH_LONG)
        .show();
    Intent service = new Intent(super.getActivity(), DownloadService.class);
    service.putExtra(DownloadIntent.DOWNLOAD_URL_PARAMETER, book.getUrl());
    service.putExtra(DownloadIntent.DOWNLOAD_ZIM_TITLE, book.getTitle());
    service.putExtra(EXTRA_BOOK, book);
    super.getActivity().startService(service);
    mConnection = new DownloadServiceConnection();
    super.getActivity()
        .bindService(service, mConnection.downloadServiceInterface, Context.BIND_AUTO_CREATE);
    ZimManageActivity manage = (ZimManageActivity) super.getActivity();
    manage.displayDownloadInterface();
  }

  public long getSpaceAvailable() {
    return new File(sharedPreferenceUtil.getPrefStorage()).getFreeSpace();
  }

  @Override
  public void selectionCallback(StorageDevice storageDevice) {
    sharedPreferenceUtil.putPrefStorage(storageDevice.getName());
    if (storageDevice.isInternal()) {
      sharedPreferenceUtil.putPrefStorageTitle(getResources().getString(R.string.internal_storage));
    } else {
      sharedPreferenceUtil.putPrefStorageTitle(getResources().getString(R.string.external_storage));
    }
  }

  public class DownloadServiceConnection {
    public DownloadServiceInterface downloadServiceInterface;

    public DownloadServiceConnection() {
      downloadServiceInterface = new DownloadServiceInterface();
    }

    public class DownloadServiceInterface implements ServiceConnection {

      @Override
      public void onServiceConnected(ComponentName className, IBinder service) {
        // We've bound to LocalService, cast the IBinder and get LocalService instance
        DownloadService.LocalBinder binder = (DownloadService.LocalBinder) service;
        mService = binder.getService();
        mBound = true;
      }

      @Override
      public void onServiceDisconnected(ComponentName arg0) {
      }
    }
  }

  public class NetworkBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      NetworkInfo network = conMan.getActiveNetworkInfo();

      if (network == null || !network.isConnected()) {
        displayNoNetworkConnection();
      }

      if ((books == null || books.isEmpty()) && network != null && network.isConnected()) {
        presenter.loadBooks();
        permissionButton.setVisibility(GONE);
        networkText.setVisibility(GONE);
        libraryRecyclerView.setVisibility(View.VISIBLE);
      }

    }
  }
}
