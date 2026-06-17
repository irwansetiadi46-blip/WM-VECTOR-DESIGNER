package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.viewmodel.VectorViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewModelTest {
    @Test
    fun testInit() {
        val vm = VectorViewModel(ApplicationProvider.getApplicationContext())
        println("ViewModel created successfully")
    }
}
